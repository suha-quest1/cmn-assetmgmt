package com.vishworks.assetmgmt;

import com.vishworks.assetmgmt.cmd.ApiServerMain;
import com.vishworks.assetmgmt.cmd.DemoDriverMain;
import com.vishworks.assetmgmt.cmd.MockCreditFacilityMain;
import com.vishworks.assetmgmt.cmd.MockSesMain;
import com.vishworks.assetmgmt.cmd.WorkerMain;
import java.util.Arrays;

/**
 * Single entry point of the executable jar. The first argument selects which service to run;
 * the remaining arguments are passed through to it. One jar with five entry points keeps the
 * Docker build to a single image, parameterised by the SERVICE build arg.
 *
 * <pre>java -jar camunda-assetmgmt.jar worker|apiserver|mockses|mockcreditfacility|demodriver [args]</pre>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    String service = args.length > 0 ? args[0] : System.getenv().getOrDefault("SERVICE", "");
    String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
    switch (service) {
      case "worker" -> WorkerMain.main(rest);
      case "apiserver" -> ApiServerMain.main(rest);
      case "mockses" -> MockSesMain.main(rest);
      case "mockcreditfacility" -> MockCreditFacilityMain.main(rest);
      case "demodriver" -> DemoDriverMain.main(rest);
      default -> {
        System.err.println("usage: java -jar camunda-assetmgmt.jar worker|apiserver|mockses|mockcreditfacility|demodriver [args]");
        System.exit(2);
      }
    }
  }
}
