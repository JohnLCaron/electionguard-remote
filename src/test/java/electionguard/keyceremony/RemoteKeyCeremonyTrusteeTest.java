package electionguard.keyceremony;

import electionguard.protogen2.RemoteKeyCeremonyTrusteeProto;
import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;

public class RemoteKeyCeremonyTrusteeTest {

  @Test
  public void testKeyCeremonyTrusteeGeneration() {
    RemoteKeyCeremonyTrusteeProto.PublicKeySet.Builder response = RemoteKeyCeremonyTrusteeProto.PublicKeySet.newBuilder();
    assertThat(response).isNotNull();
  }
}
