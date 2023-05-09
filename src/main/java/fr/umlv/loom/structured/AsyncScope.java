package fr.umlv.loom.structured;

import jdk.incubator.concurrent.StructuredTaskScope;

import java.util.Spliterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class AsyncScope<R, E extends Exception> implements AutoCloseable {
  /**
   * A callable that propagates the checked exceptions
   * @param <R> type of the result
   * @param <E> type of the checked exception, uses RuntimeException otherwise.
   */
  @FunctionalInterface
  public interface Computation<R, E extends Exception> {
    /**
     * Compute the computation.
     * @return a result
     * @throws E an exception
     * @throws InterruptedException if the computation is interrupted
     */
    R compute() throws E, InterruptedException;
  }

  /**
   * Result of an asynchronous computation
   *
   * @param <R> type of the result of the computation
   * @param <E> type of the exception thrown by the computation
   */
  public interface AsyncTask<R, E extends Exception> extends Future<R> {
    /**
     * Returns a result object corresponding to the computation if the computation is done.
     * @return a result object corresponding to the computation if the computation is done.
     * @throws IllegalStateException if the computation is not done.
     *
     * @see #isDone()
     */
    Result<R, E> result();

    /**
     * Returns the value of the computation
     * @return the value of the computation
     * @throws E the exception thrown by the computation
     * @throws InterruptedException if the task was interrupted
     * @throws IllegalStateException if the computation is not done.
     */
    R getNow() throws E, InterruptedException;
  }

  public static final class Result<R, E extends Exception> {
    public enum State { SUCCESS, CANCELLED, FAILED }

    private final State state;
    private final R result;
    private final E failure;

    private Result(State state, R result, E failure) {
      this.state = state;
      this.result = result;
      this.failure = failure;
    }

    public State state() {
      return state;
    }

    public R result() {
      if (state != State.SUCCESS) {
        throw new IllegalStateException("state not a success");
      }
      return result;
    }

    public E failure() {
      if (state != State.FAILED) {
        throw new IllegalStateException("state not a failure");
      }
      return failure;
    }

    /**
     * Returns the value of the computation
     * @return the value of the computation
     * @throws E the exception thrown by the computation
     * @throws InterruptedException if the task was interrupted
     */
    public R getNow() throws E, InterruptedException {
      return switch (state) {
        case SUCCESS -> result;
        case CANCELLED -> throw new InterruptedException();
        case FAILED -> throw failure;
      };
    }

    /**
     * Returns a stream either empty if the computation failed or was cancelled
     * or a stream with one value, the result of the computation.
     * @return a stream either empty if the computation failed or was cancelled
     *         or a stream with one value, the result of the computation.
     */
    public Stream<R> keepOnlySuccess() {
      return switch (state) {
        case SUCCESS -> Stream.of(result);
        case CANCELLED, FAILED -> Stream.empty();
      };
    }

    /**
     * Returns a binary function to {@link Stream#reduce(BinaryOperator)} two results.
     * If the two results are both success, the success merger is called, if the two results
     * are both failures the first one is returned, the second exception is added as
     * {@link Throwable#addSuppressed(Throwable) suppressed exception}.
     * If the two results does not have the same type, a success is preferred to a failure,
     * a failure is preferred to a cancellation.
     *
     * @param successMerger a binary function to merge to results
     * @return a binary function to {@link Stream#reduce(BinaryOperator)} two results.
     * @param <R> type of the result value
     * @param <E> type of the result exception
     */
    static <R, E extends Exception> BinaryOperator<Result<R,E>> merger(BinaryOperator<R> successMerger) {
      return (result1, result2) -> switch (result1.state) {
        case CANCELLED -> result2;
        case SUCCESS -> switch (result2.state) {
          case SUCCESS -> new Result<>(State.SUCCESS, successMerger.apply(result1.result, result2.result), null);
          case CANCELLED, FAILED -> result1;
        };
        case FAILED -> switch (result2.state) {
          case SUCCESS -> result2;
          case CANCELLED -> result1;
          case FAILED -> {
            result1.failure.addSuppressed(result2.failure);
            yield result1;
          }
        };
      };
    }
  }

  private final Thread ownerThread;
  private final StructuredTaskScope<R> taskScope;
  private final LinkedBlockingQueue<Future<R>> futures = new LinkedBlockingQueue<>();
  private int tasks;

  /**
   * Creates an asynchronous scope to manage several asynchronous computations.
   */
  public AsyncScope() {
    this.ownerThread = Thread.currentThread();
    this.taskScope = new StructuredTaskScope<>() {
      @Override
      protected void handleComplete(Future<R> future) {
        futures.add(future);
      }
    };
  }

  private void checkThread() {
    if (ownerThread != Thread.currentThread()) {
      throw new WrongThreadException();
    }
  }

  @Override
  public void close() {
    taskScope.close();
  }

  /**
   * Starts an asynchronous computation on a new virtual thread.
   * @param computation the computation to run.
   * @return an asynchronous task, an object that represents the result of the computation in the future.
   *
   * @see AsyncTask#result()
   */
  public AsyncTask<R, E> async(Computation<? extends R, ? extends E> computation) {
    var future = taskScope.<R>fork(computation::compute);
    tasks++;
    return new AsyncTask<R, E>() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isCancelled() {
        // FIXME why future.isCancelled() does not work here ?
        return future.state() == State.CANCELLED;
      }

      @Override
      public boolean isDone() {
        return future.isDone();
      }

      @Override
      public R get() throws InterruptedException, ExecutionException {
        return future.get();
      }

      @Override
      public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout, unit);
      }

      @Override
      public Result<R, E> result() {
        if (!future.isDone()) {
          throw new IllegalStateException("Task has not completed");
        }
        return toResult(future);
      }

      @Override
      public R getNow() throws E, InterruptedException {
        if (!future.isDone()) {
          throw new IllegalStateException("Task has not completed");
        }
        return switch (future.state()) {
          case RUNNING -> throw new AssertionError();
          case SUCCESS -> future.resultNow();
          case FAILED -> throw (E) future.exceptionNow();
          case CANCELLED -> throw new InterruptedException();
        };
      }
    };
  }

  private Result<R, E> toResult(Future<R> future) {
    return switch (future.state()) {
      case RUNNING -> throw new AssertionError();
      case SUCCESS -> new Result<>(Result.State.SUCCESS, future.resultNow(), null);
      case CANCELLED -> new Result<>(Result.State.CANCELLED, null, null);
      case FAILED -> new Result<>(Result.State.FAILED, null, (E) future.exceptionNow());
    };
  }

  /**
   * Awaits for all synchronous computations started with {@link #async(Computation)} to finish.
   * @throws InterruptedException if the current thread is interrupted
   * @throws WrongThreadException if this method is not called by the thread that has created this scope.
   */
  public void awaitAll() throws InterruptedException {
    checkThread();
    taskScope.join();
    taskScope.shutdown();
  }

  private final class ResultSpliterator implements Spliterator<Result<R,E>> {
    @Override
    public boolean tryAdvance(Consumer<? super Result<R, E>> action) {
      checkThread();
      if (tasks == 0) {
        return false;
      }
      Future<R> future;
      try {
        future = futures.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      action.accept(toResult(future));
      tasks--;
      return true;
    }

    @Override
    public Spliterator<Result<R, E>> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      checkThread();
      return tasks;
    }

    @Override
    public int characteristics() {
      return NONNULL | SIZED;
    }
  }

  /**
   * Awaits until the stream of {@link Result results} finished.
   * @param streamMapper a function that takes a stream of results and transform it to a value.
   * @return the result the stream mapper function.
   * @param <V> the type of the result of the stream mapper function
   * @throws InterruptedException if the current thread is interrupted
   * @throws WrongThreadException if this method is not called by the thread that has created this scope.
   */
  public <V> V await(Function<? super Stream<Result<R,E>>, ? extends V> streamMapper) throws InterruptedException {
    checkThread();
    var stream = StreamSupport.stream(new ResultSpliterator(), false);
    var value = streamMapper.apply(stream);
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    taskScope.shutdown();
    taskScope.join();
    return value;
  }
}
