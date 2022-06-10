package electionguard.keyceremony;

import com.github.michaelbull.result.Err;
import com.github.michaelbull.result.Ok;
import com.github.michaelbull.result.Result;
import com.google.common.flogger.FluentLogger;
import electionguard.core.ElementModP;
import electionguard.core.GroupContext;
import electionguard.core.SchnorrProof;
import electionguard.protogen.CommonRpcProto;
import electionguard.protogen.RemoteKeyCeremonyTrusteeProto;
import electionguard.protogen.RemoteKeyCeremonyTrusteeServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static electionguard.protoconvert.CommonConvertKt.importElementModP;
import static electionguard.protoconvert.CommonConvertKt.importHashedCiphertext;
import static electionguard.protoconvert.CommonConvertKt.importSchnorrProof;
import static electionguard.protoconvert.CommonConvertKt.publishHashedCiphertext;
import static electionguard.protoconvert.CommonConvertKt.publishSchnorrProof;
import static electionguard.protogen.RemoteKeyCeremonyTrusteeServiceGrpc.RemoteKeyCeremonyTrusteeServiceBlockingStub;
import static electionguard.util.KUtils.productionGroup;

/**
 * KeyCeremonyRemoteTrustee proxy, communicating over gRpc.
 * This lives in KeyCeremonyRemote, talking to a KeyCeremonyRemoteTrustee.
 */
class KeyCeremonyRemoteTrusteeProxy implements KeyCeremonyTrusteeIF {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int MAX_MESSAGE = 51 * 1000 * 1000; // 51 Mb

  GroupContext group = productionGroup();

  @Override
  public String id() {
    return trusteeId;
  }

  @Override
  public int xCoordinate() {
    return coordinate;
  }

  @Override
  public List<ElementModP> coefficientCommitments() {
    return null;
  }

  @Override
  public ElementModP electionPublicKey() {
    return null;
  }

  @Override
  public Result<PublicKeys, String> sendPublicKeys() {
    try {
      logger.atInfo().log("%s sendPublicKeys", id());
      RemoteKeyCeremonyTrusteeProto.PublicKeySetRequest request = RemoteKeyCeremonyTrusteeProto.PublicKeySetRequest.getDefaultInstance();
      RemoteKeyCeremonyTrusteeProto.PublicKeySet response = blockingStub.sendPublicKeys(request);
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("sendPublicKeys failed: %s", response.getError());
        return new Err(response.getError());
      }
      List<ElementModP> commitments = response.getCoefficientComittmentsList().stream()
              .map(p -> importElementModP(group, p))
              .toList();
      List<SchnorrProof> proofs = response.getCoefficientProofsList().stream()
              .map(p -> importSchnorrProof(group, p))
              .toList();

      return new Ok(new PublicKeys(
              response.getOwnerId(),
              response.getGuardianXCoordinate(),
              commitments,
              proofs));

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("sendPublicKeys failed: ");
      return new Err(e.getMessage());
    }
  }

  @Override
  public Result<PublicKeys, String> receivePublicKeys(PublicKeys keyset) {
    try {
      logger.atInfo().log("%s receivePublicKeys from %s", id(), keyset.getGuardianId());
      RemoteKeyCeremonyTrusteeProto.PublicKeySet.Builder request = RemoteKeyCeremonyTrusteeProto.PublicKeySet.newBuilder();
      request.setOwnerId(keyset.getGuardianId())
              .setGuardianXCoordinate(keyset.getGuardianXCoordinate());
      keyset.getCoefficientProofs().forEach(p -> request.addCoefficientProofs(publishSchnorrProof(p)));

      CommonRpcProto.ErrorResponse response = blockingStub.receivePublicKeys(request.build());
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("receivePublicKeys failed: '%s'", response.getError());
      }
      return new Err(response.getError());

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("receivePublicKeys StatusRuntimeException: ");
      return new Err("receivePublicKeys StatusRuntimeException: " + e.getMessage());
    }
  }

  @Override
  public Result<SecretKeyShare, String> sendSecretKeyShare(String guardianId) {
    try {
      RemoteKeyCeremonyTrusteeProto.PartialKeyBackupRequest request = RemoteKeyCeremonyTrusteeProto.PartialKeyBackupRequest.newBuilder().setGuardianId(guardianId).build();
      RemoteKeyCeremonyTrusteeProto.PartialKeyBackup backup = blockingStub.sendSecretKeyShare(request);
      return new Ok(new SecretKeyShare(
              backup.getGeneratingGuardianId(),
              backup.getDesignatedGuardianId(),
              backup.getDesignatedGuardianXCoordinate(),
              importHashedCiphertext(group, backup.getEncryptedCoordinate())));

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("sendPartialKeyBackup failed: ");
      return new Err("sendSecretKeyShare StatusRuntimeException: " + e.getMessage());
    }
  }

  @Override
  public Result<SecretKeyShare, String> receiveSecretKeyShare(SecretKeyShare backup) {
    try {
      RemoteKeyCeremonyTrusteeProto.PartialKeyBackup.Builder request = RemoteKeyCeremonyTrusteeProto.PartialKeyBackup.newBuilder();
      request.setGeneratingGuardianId(backup.getGeneratingGuardianId())
              .setDesignatedGuardianId(backup.getDesignatedGuardianId())
              .setDesignatedGuardianXCoordinate(backup.getDesignatedGuardianXCoordinate())
              .setEncryptedCoordinate(publishHashedCiphertext(backup.getEncryptedCoordinate()));

      RemoteKeyCeremonyTrusteeProto.PartialKeyVerification response = blockingStub.receiveSecretKeyShare(request.build());
      if (response.getError().isEmpty()) {
        return new Ok(backup);
      } else {
        return new Err(response.getError());
      }

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("receiveSecretKeyShare failed: ");
      return new Err("receiveSecretKeyShare StatusRuntimeException: " + e.getMessage());
    }
  }

  boolean saveState() {
    try {
      CommonRpcProto.ErrorResponse response = blockingStub.saveState(com.google.protobuf.Empty.getDefaultInstance());
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("saveState failed: %s", response.getError());
        return false;
      }
      return true;

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("saveState failed: ");
      e.printStackTrace();
      return false;
    }
  }

  boolean finish(boolean allOk) {
    try {
      CommonRpcProto.FinishRequest request = CommonRpcProto.FinishRequest.newBuilder().setAllOk(allOk).build();
      CommonRpcProto.ErrorResponse response = blockingStub.finish(request);
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("commit failed: %s", response.getError());
        return false;
      }
      return true;

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("commit failed: ");
      e.printStackTrace();
      return false;
    }
  }

  boolean shutdown() {
    try {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException e) {
      e.printStackTrace();
      return false;
    }
  }

  ////////////////////////////////////////////
  private final String trusteeId;
  private final int coordinate;
  private final int quorum;
  private final ManagedChannel channel;
  private final RemoteKeyCeremonyTrusteeServiceBlockingStub blockingStub;

  public int quorum() {
    return quorum;
  }

  static Builder builder() {
    return new Builder();
  }

  /** Construct client for accessing HelloWorld server using the existing channel. */
  private KeyCeremonyRemoteTrusteeProxy(String trusteeId, int coordinate, int quorum, ManagedChannel channel) {
    this.trusteeId = trusteeId;
    this.coordinate = coordinate;
    this.quorum = quorum;
    this.channel = channel;
    blockingStub = RemoteKeyCeremonyTrusteeServiceGrpc.newBlockingStub(channel);
  }

  static class Builder {
    String trusteeId;
    String target;
    int coordinate;
    int quorum;

    Builder setTrusteeId(String trusteeId) {
      this.trusteeId = trusteeId;
      return this;
    }

    Builder setUrl(String target) {
      this.target = target;
      return this;
    }

    Builder setCoordinate(int coordinate) {
      this.coordinate = coordinate;
      return this;
    }

    Builder setQuorum(int quorum) {
      this.quorum = quorum;
      return this;
    }

    KeyCeremonyRemoteTrusteeProxy build() {
      ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
              .usePlaintext()
              .enableFullStreamDecompression()
              .maxInboundMessageSize(MAX_MESSAGE).usePlaintext().build();
      return new KeyCeremonyRemoteTrusteeProxy(trusteeId, coordinate, quorum, channel);
    }
  }
}
