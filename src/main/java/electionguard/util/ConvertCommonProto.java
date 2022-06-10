package electionguard.util;

import at.favre.lib.bytes.Bytes;
import com.google.protobuf.ByteString;
import electionguard.core.ElGamalCiphertext;
import electionguard.core.ElementModP;
import electionguard.core.ElementModQ;
import electionguard.core.GenericChaumPedersenProof;
import electionguard.core.GroupContext;
import electionguard.core.HashedElGamalCiphertext;
import electionguard.core.ProductionElementModP;
import electionguard.core.ProductionElementModQ;
import electionguard.core.ProductionGroupContext;
import electionguard.core.SchnorrProof;
import electionguard.core.UInt256;
import electionguard.protogen2.CommonProto;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

public class ConvertCommonProto {

  @Nullable
  static <T, U> List<U> convertList(@Nullable List<T> from, Function<T, U> converter) {
    return from == null ? null : from.stream().map(converter).toList();
  }

  /////////////////////////////////////////////////////////////////////////////////////////
  // from proto

  @Nullable
  public static UInt256 importUInt256(@Nullable CommonProto.UInt256 modQ) {
    if (modQ == null || modQ.getValue().isEmpty()) {
      return null;
    }
    return new UInt256(modQ.getValue().toByteArray());
  }

  @Nullable
  public static ElementModQ importElementModQ(GroupContext group, @Nullable CommonProto.ElementModQ modQ) {
    if (modQ == null || modQ.getValue().isEmpty()) {
      return null;
    }
    BigInteger elem = new BigInteger(1, modQ.getValue().toByteArray());
    return new ProductionElementModQ(elem, (ProductionGroupContext) group);
  }

  @Nullable
  public static ElementModP importElementModP(GroupContext group, @Nullable CommonProto.ElementModP modP) {
    if (modP == null || modP.getValue().isEmpty()) {
      return null;
    }
    BigInteger elem = new BigInteger(1, modP.getValue().toByteArray());
    return new ProductionElementModP(elem, (ProductionGroupContext) group);
  }

  @Nullable
  public static ElGamalCiphertext importCiphertext(GroupContext group, @Nullable CommonProto.ElGamalCiphertext ciphertext) {
    if (ciphertext == null || !ciphertext.hasPad()) {
      return null;
    }
    return new ElGamalCiphertext(
            importElementModP(group, ciphertext.getPad()),
            importElementModP(group, ciphertext.getData())
    );
  }

  @Nullable
  public static HashedElGamalCiphertext importHashedCiphertext(GroupContext group, CommonProto.HashedElGamalCiphertext ciphertext) {
    if (ciphertext == null || !ciphertext.hasC0()) {
      return null;
    }

    return new HashedElGamalCiphertext(
            importElementModP(group, ciphertext.getC0()),
            Bytes.from((ciphertext.getC1().toByteArray())).array(),
            importUInt256(ciphertext.getC2()),
            ciphertext.getNumBytes()
    );
  }

  public static GenericChaumPedersenProof importChaumPedersenProof(GroupContext group, CommonProto.GenericChaumPedersenProof proof) {
    return new GenericChaumPedersenProof(
            importElementModQ(group, proof.getChallenge()),
            importElementModQ(group, proof.getResponse()));
  }

  public static SchnorrProof importSchnorrProof(GroupContext group, CommonProto.SchnorrProof proof) {
    return new SchnorrProof(
            importElementModQ(group, proof.getChallenge()),
            importElementModQ(group, proof.getResponse()));
  }

  /////////////////////////////////////////////////////////////////////////////////////////
  // to proto

  public static CommonProto.UInt256 publishUInt256(UInt256 modQ) {
    CommonProto.UInt256.Builder builder = CommonProto.UInt256.newBuilder();
    builder.setValue(ByteString.copyFrom(modQ.getBytes()));
    return builder.build();
  }

  public static CommonProto.UInt256 publishUInt256fromQ(ElementModQ modQ) {
    CommonProto.UInt256.Builder builder = CommonProto.UInt256.newBuilder();
    builder.setValue(ByteString.copyFrom(modQ.byteArray()));
    return builder.build();
  }

  public static CommonProto.ElementModQ publishElementModQ(ElementModQ modQ) {
    CommonProto.ElementModQ.Builder builder = CommonProto.ElementModQ.newBuilder();
    builder.setValue(ByteString.copyFrom(modQ.byteArray()));
    return builder.build();
  }

  public static CommonProto.ElementModP publishElementModP(ElementModP modP) {
    CommonProto.ElementModP.Builder builder = CommonProto.ElementModP.newBuilder();
    builder.setValue(ByteString.copyFrom(modP.byteArray()));
    return builder.build();
  }

  public static CommonProto.ElGamalCiphertext publishCiphertext(ElGamalCiphertext ciphertext) {
    CommonProto.ElGamalCiphertext.Builder builder = CommonProto.ElGamalCiphertext.newBuilder();
    builder.setPad(publishElementModP(ciphertext.getPad()));
    builder.setData(publishElementModP(ciphertext.getData()));
    return builder.build();
  }

  public static CommonProto.HashedElGamalCiphertext publishHashedCiphertext(HashedElGamalCiphertext ciphertext) {
    CommonProto.HashedElGamalCiphertext.Builder builder = CommonProto.HashedElGamalCiphertext.newBuilder();
    builder.setC0(publishElementModP(ciphertext.getC0()));
    builder.setC1(ByteString.copyFrom(ciphertext.getC1()));
    builder.setC2(publishUInt256(ciphertext.getC2()));
    builder.setNumBytes(ciphertext.getNumBytes());
    return builder.build();
  }

  public static CommonProto.GenericChaumPedersenProof publishChaumPedersenProof(GenericChaumPedersenProof proof) {
    CommonProto.GenericChaumPedersenProof.Builder builder = CommonProto.GenericChaumPedersenProof.newBuilder();
    builder.setChallenge(publishElementModQ(proof.getC()));
    builder.setResponse(publishElementModQ(proof.getR()));
    return builder.build();
  }

  public static CommonProto.SchnorrProof publishSchnorrProof(SchnorrProof proof) {
    CommonProto.SchnorrProof.Builder builder = CommonProto.SchnorrProof.newBuilder();
    builder.setChallenge(publishElementModQ(proof.getChallenge()));
    builder.setResponse(publishElementModQ(proof.getResponse()));
    return builder.build();
  }
  
}
