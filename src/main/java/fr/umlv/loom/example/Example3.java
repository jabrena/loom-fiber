package fr.umlv.loom.example;

import java.util.concurrent.StructuredTaskScope;

public class Example3 {
  // async calls with sleep, how virtual are mapped to carrier threads
  public static void main(String[] args) throws InterruptedException {
    var start = System.currentTimeMillis();
    try(var scope = new StructuredTaskScope<>()) {
      var future1 = scope.fork(() -> {
        System.out.println(Thread.currentThread());
        Thread.sleep(1_000);
        System.out.println(Thread.currentThread());
        return 40;
      });
      var future2 = scope.fork(() -> {
        System.out.println(Thread.currentThread());
        Thread.sleep(1_000);
        System.out.println(Thread.currentThread());
        return 2;
      });
      scope.join();
      var sum = future1.resultNow() + future2.resultNow();
      System.out.println("sum = " + sum);
    }
    var end = System.currentTimeMillis();
    System.out.println("elapsed time = " + (end - start) + " ms");
  }
}
