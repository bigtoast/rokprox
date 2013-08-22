package com.github.bigtoast.rokprox;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import akka.actor.ActorSystem;
import scala.concurrent.duration.FiniteDuration;
import java.util.concurrent.TimeUnit;
import com.github.bigtoast.rokprox.RokProx; // to appease intellij

public class RokProxTests {

  @BeforeClass
  public static void beforeAll() {
    //system = ActorSystem.create("JavaAPI", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    //system.shutdown();
    //system = null;
  }

  @Test
  public void createLocalProxy() {
  	ActorSystem sys = ActorSystem.create("java-testers");
    RokProxy prox   = RokProx.proxy("java-test").from("127.0.0.1:11111").to("127.0.0.1:22222").jBuild(sys);
    FiniteDuration dur = new FiniteDuration(1, TimeUnit.SECONDS);

    prox.interrupt( dur );
    prox.restore();
    prox.pause(dur);

    try { Thread.sleep(1000); } catch( Exception e ){ }

    prox.shutdown();
    sys.shutdown();
    assertTrue(true);
  }

  @Test
  public void createClientBuilder() {
  	RokProxyClient client = RokProx.client("localhost:8989").jBuild();

  	RokProxy prox = client.builder().name("tester").from("127.0.0.1:11111").to("127.0.0.1:22222").jBuild();

  	try { Thread.sleep(1000); } catch( Exception e ){ }

  	prox.shutdown();
  	client.exit();
  }

}
