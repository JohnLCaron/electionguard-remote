package electionguard.decrypt;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import electionguard.core.ElGamalCiphertext;
import electionguard.core.ElementModP;
import electionguard.core.ElementModQ;
import electionguard.core.GroupContext;
import electionguard.protogen2.CommonProto;
import electionguard.protogen2.CommonRpcProto;
import electionguard.protogen2.DecryptingTrusteeProto;
import electionguard.protogen2.DecryptingTrusteeServiceGrpc;
import electionguard.util.ConvertCommonProto;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static electionguard.util.KUtils.productionGroup;

/**
 * A Remote Trustee client proxy, communicating over gRpc.
 * This lives in KeyCeremonyRemote, talking to a DecryptingRemoteTrustee.
 */
class RemoteDecryptingTrusteeProxy implements DecryptingTrusteeIF  {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  public String id() {
    return trusteeId;
  }

  @Override
  public int xCoordinate() {
    return xCoordinate;
  }

  @Override
  public ElementModP electionPublicKey() {
    return electionPublicKey;
  }

  @Override
  public List<DirectDecryptionAndProof> directDecrypt(
          GroupContext group,
          List<ElGamalCiphertext> texts,
          ElementModQ extendedBaseHash,
          @Nullable ElementModQ nonce) {
    try {
      List<CommonProto.ElGamalCiphertext> ptexts = texts.stream()
              .map(ConvertCommonProto::publishCiphertext)
              .toList();

      DecryptingTrusteeProto.DirectDecryptionRequest.Builder request = DecryptingTrusteeProto.DirectDecryptionRequest.newBuilder()
              .addAllText(ptexts)
              .setExtendedBaseHash(ConvertCommonProto.publishElementModQ(extendedBaseHash));

      DecryptingTrusteeProto.DirectDecryptionResponse response = blockingStub.directDecrypt(request.build());
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("directDecrypt failed: %s", response.getError());
        return ImmutableList.of();
      }
      return response.getResultsList().stream()
              .map(r -> convertDecryptionProofTuple(group, r))
              .toList();

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("directDecrypt failed: ");
      return ImmutableList.of();
    }
  }

  private DirectDecryptionAndProof convertDecryptionProofTuple(GroupContext group, DecryptingTrusteeProto.DirectDecryptionResult proto) {
    return new DirectDecryptionAndProof(
            ConvertCommonProto.importElementModP(group, proto.getDecryption()),
            ConvertCommonProto.importChaumPedersenProof(group, proto.getProof()));
  }

  @Override
  public List<CompensatedDecryptionAndProof> compensatedDecrypt(
          GroupContext group,
          String missingGuardianId,
          List<ElGamalCiphertext> texts,
          ElementModQ extendedBaseHash,
          @Nullable ElementModQ nonce) {

    try {
      List<CommonProto.ElGamalCiphertext> ptexts = texts.stream()
              .map(ConvertCommonProto::publishCiphertext)
              .toList();

      DecryptingTrusteeProto.CompensatedDecryptionRequest.Builder request = DecryptingTrusteeProto.CompensatedDecryptionRequest.newBuilder()
              .setMissingGuardianId(missingGuardianId)
              .addAllText(ptexts)
              .setExtendedBaseHash(ConvertCommonProto.publishElementModQ(extendedBaseHash));

      DecryptingTrusteeProto.CompensatedDecryptionResponse response = blockingStub.compensatedDecrypt(request.build());
      if (!response.getError().isEmpty()) {
        logger.atSevere().log("compensatedDecrypt failed: %s", response.getError());
        return ImmutableList.of();
      }
      return response.getResultsList().stream()
              .map(r -> convertDecryptionProofRecovery(group, r))
              .toList();

    } catch (StatusRuntimeException e) {
      logger.atSevere().withCause(e).log("compensatedDecrypt failed");
      return ImmutableList.of();
    }
  }

  private CompensatedDecryptionAndProof convertDecryptionProofRecovery(GroupContext group, DecryptingTrusteeProto.CompensatedDecryptionResult proto) {
    return new CompensatedDecryptionAndProof(
            ConvertCommonProto.importElementModP(group, proto.getDecryption()),
            ConvertCommonProto.importChaumPedersenProof(group, proto.getProof()),
            ConvertCommonProto.importElementModP(group, proto.getRecoveryPublicKey()));
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
  private final GroupContext group;
  private final String trusteeId;
  private final int xCoordinate;
  private final ElementModP electionPublicKey;
  private final ManagedChannel channel;
  private final DecryptingTrusteeServiceGrpc.DecryptingTrusteeServiceBlockingStub blockingStub;

  static Builder builder() {
    return new Builder();
  }

  private RemoteDecryptingTrusteeProxy(String trusteeId, int xCoordinate, ElementModP electionPublicKey, ManagedChannel channel) {
    this.trusteeId = Preconditions.checkNotNull(trusteeId);
    Preconditions.checkArgument(xCoordinate > 0);
    this.xCoordinate = xCoordinate;
    this.electionPublicKey = Preconditions.checkNotNull(electionPublicKey);
    this.channel = Preconditions.checkNotNull(channel);
    this.blockingStub = DecryptingTrusteeServiceGrpc.newBlockingStub(channel);

    this.group = productionGroup();
  }

  static class Builder {
    String trusteeId;
    String target;
    int xCoordinate;
    ElementModP electionPublicKey;

    Builder setTrusteeId(String trusteeId) {
      this.trusteeId = trusteeId;
      return this;
    }

    Builder setUrl(String target) {
      this.target = target;
      return this;
    }

    Builder setXCoordinate(int xCoordinate) {
      this.xCoordinate = xCoordinate;
      return this;
    }

    Builder setElectionPublicKey(ElementModP electionPublicKey) {
      this.electionPublicKey = electionPublicKey;
      return this;
    }

    RemoteDecryptingTrusteeProxy build() {
      ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
              .usePlaintext()
              .keepAliveTime(1, TimeUnit.MINUTES)
              // .enableFullStreamDecompression()
              // .maxInboundMessageSize(MAX_MESSAGE)
              .build();
      return new RemoteDecryptingTrusteeProxy(trusteeId, xCoordinate, electionPublicKey, channel);
    }
  }
}
