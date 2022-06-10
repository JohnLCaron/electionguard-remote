package electionguard.util;

import electionguard.core.GroupContext;
import electionguard.core.PowRadixOption;
import electionguard.core.ProductionMode;


public class KUtils {

  public static GroupContext productionGroup() {
    return electionguard.core.GroupKt.productionGroup(PowRadixOption.LOW_MEMORY_USE, ProductionMode.Mode4096);
  }

}
