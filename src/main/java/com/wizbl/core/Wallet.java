/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.core;

import com.wizbl.api.GrpcAPI;
import com.wizbl.api.GrpcAPI.*;
import com.wizbl.api.GrpcAPI.Return.response_code;
import com.wizbl.api.GrpcAPI.TransactionExtention.Builder;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.crypto.Hash;
import com.wizbl.common.overlay.discover.node.NodeHandler;
import com.wizbl.common.overlay.discover.node.NodeManager;
import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.runtime.Runtime;
import com.wizbl.common.runtime.RuntimeImpl;
import com.wizbl.common.runtime.config.VMConfig;
import com.wizbl.common.runtime.vm.program.ProgramResult;
import com.wizbl.common.runtime.vm.program.invoke.ProgramInvokeFactoryImpl;
import com.wizbl.common.storage.DepositImpl;
import com.wizbl.common.utils.Base58;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.common.utils.Utils;
import com.wizbl.core.actuator.Actuator;
import com.wizbl.core.actuator.ActuatorFactory;
import com.wizbl.core.capsule.*;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.Parameter.ChainParameters;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.*;
import com.wizbl.core.exception.*;
import com.wizbl.core.net.message.TransactionMessage;
import com.wizbl.core.net.node.NodeImpl;
import com.wizbl.protos.Contract.AssetIssueContract;
import com.wizbl.protos.Contract.CreateSmartContract;
import com.wizbl.protos.Contract.TransferContract;
import com.wizbl.protos.Contract.TriggerSmartContract;
import com.wizbl.protos.Protocol;
import com.wizbl.protos.Protocol.*;
import com.wizbl.protos.Protocol.SmartContract.ABI;
import com.wizbl.protos.Protocol.SmartContract.ABI.Entry.StateMutabilityType;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.wizbl.protos.Protocol.Transaction.Result.code;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;

import static com.wizbl.core.config.Parameter.DatabaseConstants.EXCHANGE_COUNT_LIMIT_MAX;
import static com.wizbl.core.config.Parameter.DatabaseConstants.PROPOSAL_COUNT_LIMIT_MAX;

@Slf4j
@Component
public class Wallet {

  @Getter
  private final ECKey ecKey;
  @Autowired
  private NodeImpl p2pNode;
  @Autowired
  private Manager dbManager;
  @Autowired
  private NodeManager nodeManager;
  private static String addressPreFixString = Constant.ADD_PRE_FIX_STRING_TESTNET;  //default testnet
  private static byte [] addressPreFixByte = Constant.ADD_PRE_FIX_BYTE_TESTNET;

  private int minEffectiveConnection = Args.getInstance().getMinEffectiveConnection();

  /**
   * Creates a new Wallet with a random ECKey.
   */
  public Wallet() {
    this.ecKey = new ECKey(Utils.getRandom());
  }

  /**
   * Creates a Wallet with an existing ECKey.
   */
  public Wallet(final ECKey ecKey) {
    this.ecKey = ecKey;
    logger.info("wallet address: {}", ByteArray.toHexString(this.ecKey.getAddress()));
  }

  public static boolean isConstant(ABI abi, TriggerSmartContract triggerSmartContract)
      throws ContractValidateException {
    try {
      boolean constant = isConstant(abi, getSelector(triggerSmartContract.getData().toByteArray()));
      if (constant) {
        if (!Args.getInstance().isSupportConstant()) {
          throw new ContractValidateException("this node don't support constant");
        }
      }
      return constant;
    } catch (ContractValidateException e) {
      throw e;
    } catch (Exception e) {
      return false;
    }
  }

  public byte[] getAddress() {
    return ecKey.getAddress();
  }

  public static String getAddressPreFixString() {
    return addressPreFixString;
  }

  public static void setAddressPreFixString(String addressPreFixString) {
    Wallet.addressPreFixString = addressPreFixString;
  }

  public static byte[] getAddressPreFixByte() {
    return addressPreFixByte;
  }

  public static void setAddressPreFixByte(byte [] addressPreFixByte) {
    Wallet.addressPreFixByte = addressPreFixByte;
  }

  public static boolean addressValid(byte[] address) {
    if (ArrayUtils.isEmpty(address)) {
      logger.warn("Warning: Address is empty !!");
      return false;
    }
    if (address.length != Constant.ADDRESS_SIZE / 2) {
      logger.warn(
          "Warning: Address length need " + Constant.ADDRESS_SIZE + " but " + address.length
              + " !!");
      return false;
    }
    if (prefixCheck(address, addressPreFixByte)) {
      logger.warn("Warning: Invalid address prefix !!");
      return false;
    }
    //Other rule;
    return true;
  }

  public static boolean prefixCheck(byte[] address, byte[] addressPreFixByte) {
    for (int i = 0; i < addressPreFixByte.length; i++) {
      if (address[i] != addressPreFixByte[i]) {
        logger.warn("Warning: Invalid prefix !!");
        return true;
      }
    }
    return false;
  }

  public static String encode58Check(byte[] input) {
    byte[] hash0 = Sha256Hash.hash(input);
    byte[] hash1 = Sha256Hash.hash(hash0);
    byte[] inputCheck = new byte[input.length + 4];
    System.arraycopy(input, 0, inputCheck, 0, input.length);
    System.arraycopy(hash1, 0, inputCheck, input.length, 4);
    return Base58.encode(inputCheck);
  }

  private static byte[] decode58Check(String input) {
    byte[] decodeCheck = Base58.decode(input);
    if (decodeCheck.length <= 4) {
      return null;
    }
    byte[] decodeData = new byte[decodeCheck.length - 4];
    System.arraycopy(decodeCheck, 0, decodeData, 0, decodeData.length);
    byte[] hash0 = Sha256Hash.hash(decodeData);
    byte[] hash1 = Sha256Hash.hash(hash0);
    if (hash1[0] == decodeCheck[decodeData.length] &&
        hash1[1] == decodeCheck[decodeData.length + 1] &&
        hash1[2] == decodeCheck[decodeData.length + 2] &&
        hash1[3] == decodeCheck[decodeData.length + 3]) {
      return decodeData;
    }
    return null;
  }

  public static byte[] generateContractAddress(Transaction trx) {

    CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(trx);
    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    TransactionCapsule trxCap = new TransactionCapsule(trx);
    byte[] txRawDataHash = trxCap.getTransactionId().getBytes();

    byte[] combined = new byte[txRawDataHash.length + ownerAddress.length];
    System.arraycopy(txRawDataHash, 0, combined, 0, txRawDataHash.length);
    System.arraycopy(ownerAddress, 0, combined, txRawDataHash.length, ownerAddress.length);

    return Hash.sha3omit12(combined);

  }

  public static byte[] generateContractAddress(byte[] ownerAddress, byte[] txRawDataHash) {

    byte[] combined = new byte[txRawDataHash.length + ownerAddress.length];
    System.arraycopy(txRawDataHash, 0, combined, 0, txRawDataHash.length);
    System.arraycopy(ownerAddress, 0, combined, txRawDataHash.length, ownerAddress.length);

    return Hash.sha3omit12(combined);

  }

  public static byte[] generateContractAddress(byte[] transactionRootId, long nonce) {
    byte[] nonceBytes = Longs.toByteArray(nonce);
    byte[] combined = new byte[transactionRootId.length + nonceBytes.length];
    System.arraycopy(transactionRootId, 0, combined, 0, transactionRootId.length);
    System.arraycopy(nonceBytes, 0, combined, transactionRootId.length, nonceBytes.length);

    return Hash.sha3omit12(combined);
  }

  public static byte[] decodeFromBase58Check(String addressBase58) {
    if (StringUtils.isEmpty(addressBase58)) {
      logger.warn("Warning: Address is empty !!");
      return null;
    }
    byte[] address = decode58Check(addressBase58);
    if (address == null) {
      return null;
    }

    if (!addressValid(address)) {
      return null;
    }

    return address;
  }


  public Account getAccount(Account account) {
    AccountStore accountStore = dbManager.getAccountStore();
    AccountCapsule accountCapsule = accountStore.get(account.getAddress().toByteArray());
    if (accountCapsule == null) {
      return null;
    }
    BandwidthProcessor processor = new BandwidthProcessor(dbManager);
    processor.updateUsage(accountCapsule);

    EnergyProcessor energyProcessor = new EnergyProcessor(dbManager);
    energyProcessor.updateUsage(accountCapsule);

    ManaProcessor manaProcessor = new ManaProcessor(dbManager);
    manaProcessor.updateUsage(accountCapsule);

    long genesisTimeStamp = dbManager.getGenesisBlock().getTimeStamp();
    accountCapsule.setLatestConsumeTime(genesisTimeStamp
        + ChainConstant.BLOCK_PRODUCED_INTERVAL * accountCapsule.getLatestConsumeTime());
    accountCapsule.setLatestConsumeFreeTime(genesisTimeStamp
        + ChainConstant.BLOCK_PRODUCED_INTERVAL * accountCapsule.getLatestConsumeFreeTime());
    accountCapsule.setLatestConsumeTimeForEnergy(genesisTimeStamp
        + ChainConstant.BLOCK_PRODUCED_INTERVAL * accountCapsule.getLatestConsumeTimeForEnergy());

    return accountCapsule.getInstance();
  }


  public Account getAccountById(Account account) {
    AccountStore accountStore = dbManager.getAccountStore();
    AccountIdIndexStore accountIdIndexStore = dbManager.getAccountIdIndexStore();
    byte[] address = accountIdIndexStore.get(account.getAccountId());
    if (address == null) {
      return null;
    }
    AccountCapsule accountCapsule = accountStore.get(address);
    if (accountCapsule == null) {
      return null;
    }
    BandwidthProcessor processor = new BandwidthProcessor(dbManager);
    processor.updateUsage(accountCapsule);

    EnergyProcessor energyProcessor = new EnergyProcessor(dbManager);
    energyProcessor.updateUsage(accountCapsule);

    ManaProcessor manaProcessor = new ManaProcessor(dbManager);
    manaProcessor.updateUsage(accountCapsule);

    return accountCapsule.getInstance();
  }

  /**
   * Create a transaction.
   */
  /*public Transaction createTransaction(byte[] address, String to, long amount) {
    long balance = getBalance(address);
    return new TransactionCapsule(address, to, amount, balance, utxoStore).getInstance();
  } */

  /**
   * Create a transaction by contract.
   */
  @Deprecated
  public Transaction createTransaction(TransferContract contract) {
    AccountStore accountStore = dbManager.getAccountStore();
    return new TransactionCapsule(contract, accountStore).getInstance();
  }


  /**
   * createTransactionCapsule메소드는
   * &emst 1. message를 TransactionCapsule객체인 trx에 저장하고,
   * &emst 2. trx에 저장된 ContractType에 맞는 Actuator 객체를 이용하여 trx의 검증을 진행함.
   * &emst 3. 검증이 끝나면 transaction이 저장(참조)되는 블록, expiration time, timestamp를 trx에 저장하여 반환함.
   * @param message
   * @param contractType
   * @return
   * @throws ContractValidateException
   */
  public TransactionCapsule createTransactionCapsule(com.google.protobuf.Message message,
      ContractType contractType) throws ContractValidateException {
    TransactionCapsule trx = new TransactionCapsule(message, contractType);
    if (contractType != ContractType.CreateSmartContract
        && contractType != ContractType.TriggerSmartContract) {
      List<Actuator> actList = ActuatorFactory.createActuator(trx, dbManager);
      for (Actuator act : actList) {
        // actList에 저장된 actuator에서 transaction에 대한 검증(validation)작업을 진행함.
        // 검증에 실패하면 ContractValidateException 예외가 발생함.
        act.validate(); // 개별 actuator의 validate 메소드를 호출함.
      }
    }

    // CreateSmartContract의 경우에는 신규Contract에서 사용자의 Resource 소모 비율을 검증함.
    if (contractType == ContractType.CreateSmartContract) {
      CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(trx.getInstance());
      long percent = contract.getNewContract().getConsumeUserResourcePercent();
      if (percent < 0 || percent > 100) {
        throw new ContractValidateException("percent must be >= 0 and <= 100");
      }
    }

    // 검증이 끝나면 transaction이 참조(저장)되는 블록 선정, expiration time, timestamp를 trx 객체에 저장하여 이를 반환함.
    try {
      BlockId blockId = dbManager.getHeadBlockId();
      if (Args.getInstance().getTrxReferenceBlock().equals("solid")){
        blockId = dbManager.getSolidBlockId();
      }
      trx.setReference(blockId.getNum(), blockId.getBytes());
      long expiration = dbManager.getHeadBlockTimeStamp() + Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;
      trx.setExpiration(expiration);
      trx.setTimestamp();
    } catch (Exception e) {
      logger.error("Create transaction capsule failed.", e);
    }
    return trx;
  }

  /**
   * Broadcast a transaction. <br/>
   * broadcasting을 위한 조건 <br/>
   * &emsp 1. minEffectiveConnection 조건을 충족하는지 여부
   * &emsp 2. pendingTransaction + repushTransaction으로 인해서 서버가 처리 못하는 상황인지를 체크
   * &emsp 3. server의 상태가 Block Generating인지를 체크
   * &emsp 4. 이미 처리된 transaction인지를 체크
   * &emsp 5. pushTransaction
   * &emsp 6. broadcast
   * @param signaturedTransaction
   * @return
   */
  public GrpcAPI.Return broadcastTransaction(Transaction signaturedTransaction) {
    GrpcAPI.Return.Builder builder = GrpcAPI.Return.newBuilder();
    TransactionCapsule trx = new TransactionCapsule(signaturedTransaction);
    Message message = new TransactionMessage(signaturedTransaction);

    try{
      // 1. minEffectiveConnection을 기준으로 배포 가능 여부 체크
      if (minEffectiveConnection != 0) {
        if (p2pNode.getActivePeer().isEmpty()) {
          logger.warn("Broadcast transaction {} failed, no connection.", trx.getTransactionId());
          return builder.setResult(false).setCode(response_code.NO_CONNECTION)
              .setMessage(ByteString.copyFromUtf8("no connection"))
              .build();
        }

        int count = (int) p2pNode.getActivePeer().stream()
            .filter(p -> !p.isNeedSyncFromUs() && !p.isNeedSyncFromPeer())
            .count();

        if (count < minEffectiveConnection) {
          String info = "effective connection:" + count + " lt minEffectiveConnection:" + minEffectiveConnection;
          logger.warn("Broadcast transaction {} failed, {}.", trx.getTransactionId(), info);
          return builder.setResult(false).setCode(response_code.NOT_ENOUGH_EFFECTIVE_CONNECTION)
              .setMessage(ByteString.copyFromUtf8(info))
              .build();
        }
      }

      // 2. server에 pendingTransaction + repushTransaction이 많아서 처리가 되지 않는 경우 검증.
      if (dbManager.isTooManyPending()) {
        logger.warn("Broadcast transaction {} failed, too many pending.", trx.getTransactionId());
        return builder.setResult(false).setCode(response_code.SERVER_BUSY).build();
      }

      // 3. server의 상태가 generation block 중인지를 확인
      if (dbManager.isGeneratingBlock()) {
        logger.warn("Broadcast transaction {} failed, is generating block.", trx.getTransactionId());
        return builder.setResult(false).setCode(response_code.SERVER_BUSY).build();
      }

      // 4. transaction이 이미 처리되어 transactionIdCache에 저장되어 있는지를 확인
      if (dbManager.getTransactionIdCache().getIfPresent(trx.getTransactionId()) != null) {
        logger.warn("Broadcast transaction {} failed, is already exist.", trx.getTransactionId());
        return builder.setResult(false).setCode(response_code.DUP_TRANSACTION_ERROR).build();
      } else {
        dbManager.getTransactionIdCache().put(trx.getTransactionId(), true);
      }
      if (dbManager.getDynamicPropertiesStore().supportVM()) {
        trx.resetResult();
      }
      // 5. pushTransaction
      dbManager.pushTransaction(trx);
      // 6. broadcast
      p2pNode.broadcast(message);
      logger.info("Broadcast transaction {} successfully.", trx.getTransactionId());

      return builder.setResult(true).setCode(response_code.SUCCESS).build();
    } catch (ValidateSignatureException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.SIGERROR)
          .setMessage(ByteString.copyFromUtf8("validate signature error"))
          .build();
    } catch (ContractValidateException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
          .setMessage(ByteString.copyFromUtf8("contract validate error : " + e.getMessage()))
          .build();
    } catch (ContractExeException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
          .setMessage(ByteString.copyFromUtf8("contract execute error : " + e.getMessage()))
          .build();
    } catch (AccountResourceInsufficientException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.BANDWITH_ERROR)
          .setMessage(ByteString.copyFromUtf8("AccountResourceInsufficient error"))
          .build();
    } catch (DupTransactionException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.DUP_TRANSACTION_ERROR)
          .setMessage(ByteString.copyFromUtf8("dup transaction"))
          .build();
    } catch (TaposException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.TAPOS_ERROR)
          .setMessage(ByteString.copyFromUtf8("Tapos check error"))
          .build();
    } catch (TooBigTransactionException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.TOO_BIG_TRANSACTION_ERROR)
          .setMessage(ByteString.copyFromUtf8("transaction size is too big"))
          .build();
    } catch (TransactionExpirationException e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.TRANSACTION_EXPIRATION_ERROR)
          .setMessage(ByteString.copyFromUtf8("transaction expired"))
          .build();
    } catch (Exception e) {
      logger.error("Broadcast transaction {} failed, {}.", trx.getTransactionId(), e.getMessage());
      return builder.setResult(false).setCode(response_code.OTHER_ERROR)
          .setMessage(ByteString.copyFromUtf8("other error : " + e.getMessage()))
          .build();
    }
  }

  public TransactionCapsule getTransactionSign(TransactionSign transactionSign) {
    byte[] privateKey = transactionSign.getPrivateKey().toByteArray();
    TransactionCapsule trx = new TransactionCapsule(transactionSign.getTransaction());
    trx.sign(privateKey);
    return trx;
  }

  public byte[] pass2Key(byte[] passPhrase) {
    return Sha256Hash.hash(passPhrase);
  }

  public byte[] createAdresss(byte[] passPhrase) {
    byte[] privateKey = pass2Key(passPhrase);
    ECKey ecKey = ECKey.fromPrivate(privateKey);
    return ecKey.getAddress();
  }

  public Block getNowBlock() {
    List<BlockCapsule> blockList = dbManager.getBlockStore().getBlockByLatestNum(1);
    if (CollectionUtils.isEmpty(blockList)) {
      return null;
    } else {
      return blockList.get(0).getInstance();
    }
  }

  public Block getBlockByNum(long blockNum) {
    try {
      return dbManager.getBlockByNum(blockNum).getInstance();
    } catch (StoreException e) {
      logger.info(e.getMessage());
      return null;
    }
  }

  public long getTransactionCountByBlockNum(long blockNum) {
    long count = 0;

    try {
      Block block = dbManager.getBlockByNum(blockNum).getInstance();
      count = block.getTransactionsCount();
    } catch (StoreException e) {
      logger.error(e.getMessage());
    }

    return count;
  }

  public WitnessList getWitnessList() {
    WitnessList.Builder builder = WitnessList.newBuilder();
    List<WitnessCapsule> witnessCapsuleList = dbManager.getWitnessStore().getAllWitnesses();
    witnessCapsuleList
        .forEach(witnessCapsule -> builder.addWitnesses(witnessCapsule.getInstance()));
    return builder.build();
  }

  public ProposalList getProposalList() {
    ProposalList.Builder builder = ProposalList.newBuilder();
    List<ProposalCapsule> proposalCapsuleList = dbManager.getProposalStore().getAllProposals();
    proposalCapsuleList
        .forEach(proposalCapsule -> builder.addProposals(proposalCapsule.getInstance()));
    return builder.build();
  }

  public DelegatedResourceList getDelegatedResource(ByteString fromAddress, ByteString toAddress) {
    DelegatedResourceList.Builder builder = DelegatedResourceList.newBuilder();
    byte[] dbKey = DelegatedResourceCapsule
        .createDbKey(fromAddress.toByteArray(), toAddress.toByteArray());
    DelegatedResourceCapsule delegatedResourceCapsule = dbManager.getDelegatedResourceStore()
        .get(dbKey);
    if (delegatedResourceCapsule != null) {
      builder.addDelegatedResource(delegatedResourceCapsule.getInstance());
    }
    return builder.build();
  }

  public DelegatedResourceAccountIndex getDelegatedResourceAccountIndex(ByteString address) {
    DelegatedResourceAccountIndexCapsule accountIndexCapsule =
        dbManager.getDelegatedResourceAccountIndexStore().get(address.toByteArray());
    if (accountIndexCapsule != null) {
      return accountIndexCapsule.getInstance();
    } else {
      return null;
    }
  }

  public ExchangeList getExchangeList() {
    ExchangeList.Builder builder = ExchangeList.newBuilder();
    List<ExchangeCapsule> exchangeCapsuleList = dbManager.getExchangeStoreFinal().getAllExchanges();

    exchangeCapsuleList
        .forEach(exchangeCapsule -> builder.addExchanges(exchangeCapsule.getInstance()));
    return builder.build();
  }

  public Protocol.ChainParameters getChainParameters() {
    Protocol.ChainParameters.Builder builder = Protocol.ChainParameters.newBuilder();

    Arrays.stream(ChainParameters.values()).forEach(parameters -> {
      try {
        String methodName = Wallet.makeUpperCamelMethod(parameters.name());
        builder.addChainParameter(Protocol.ChainParameters.ChainParameter.newBuilder()
            .setKey(methodName)
            .setValue((Long) DynamicPropertiesStore.class.getDeclaredMethod(methodName)
                .invoke(dbManager.getDynamicPropertiesStore()))
            .build());
      } catch (Exception ex) {
        logger.error("get chainParameter error,", ex);
      }

    });

    return builder.build();
  }

  public static String makeUpperCamelMethod(String originName) {
    return "get" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, originName)
        .replace("_", "");
  }

  public AssetIssueList getAssetIssueList() {
    AssetIssueList.Builder builder = AssetIssueList.newBuilder();

    dbManager.getAssetIssueStoreFinal().getAllAssetIssues()
        .forEach(issueCapsule -> builder.addAssetIssue(issueCapsule.getInstance()));

    return builder.build();
  }


  public AssetIssueList getAssetIssueList(long offset, long limit) {
    AssetIssueList.Builder builder = AssetIssueList.newBuilder();

    List<AssetIssueCapsule> assetIssueList =
        dbManager.getAssetIssueStoreFinal().getAssetIssuesPaginated(offset, limit);

    if (CollectionUtils.isEmpty(assetIssueList)) {
      return null;
    }

    assetIssueList.forEach(issueCapsule -> builder.addAssetIssue(issueCapsule.getInstance()));
    return builder.build();
  }

  public AssetIssueList getAssetIssueByAccount(ByteString accountAddress) {
    if (accountAddress == null || accountAddress.isEmpty()) {
      return null;
    }

    List<AssetIssueCapsule> assetIssueCapsuleList =
        dbManager.getAssetIssueStoreFinal().getAllAssetIssues();

    AssetIssueList.Builder builder = AssetIssueList.newBuilder();
    assetIssueCapsuleList.stream()
        .filter(assetIssueCapsule -> assetIssueCapsule.getOwnerAddress().equals(accountAddress))
        .forEach(issueCapsule -> {
          builder.addAssetIssue(issueCapsule.getInstance());
        });

    return builder.build();
  }

  public AccountNetMessage getAccountNet(ByteString accountAddress) {
    if (accountAddress == null || accountAddress.isEmpty()) {
      return null;
    }
    AccountNetMessage.Builder builder = AccountNetMessage.newBuilder();
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(accountAddress.toByteArray());
    if (accountCapsule == null) {
      return null;
    }

    BandwidthProcessor processor = new BandwidthProcessor(dbManager);
    processor.updateUsage(accountCapsule);

    long netLimit = processor
        .calculateGlobalNetLimit(accountCapsule);
    long freeNetLimit = dbManager.getDynamicPropertiesStore().getFreeNetLimit();
    long totalNetLimit = dbManager.getDynamicPropertiesStore().getTotalNetLimit();
    long totalNetWeight = dbManager.getDynamicPropertiesStore().getTotalNetWeight();

    Map<String, Long> assetNetLimitMap = new HashMap<>();
    Map<String, Long> allFreeAssetNetUsage;
    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      allFreeAssetNetUsage = accountCapsule.getAllFreeAssetNetUsage();
      allFreeAssetNetUsage.keySet().forEach(asset -> {
        byte[] key = ByteArray.fromString(asset);
        assetNetLimitMap
            .put(asset, dbManager.getAssetIssueStore().get(key).getFreeAssetNetLimit());
      });
    } else {
      allFreeAssetNetUsage = accountCapsule.getAllFreeAssetNetUsageV2();
      allFreeAssetNetUsage.keySet().forEach(asset -> {
        byte[] key = ByteArray.fromString(asset);
        assetNetLimitMap
            .put(asset, dbManager.getAssetIssueV2Store().get(key).getFreeAssetNetLimit());
      });
    }

    builder.setFreeNetUsed(accountCapsule.getFreeNetUsage())
        .setFreeNetLimit(freeNetLimit)
        .setNetUsed(accountCapsule.getNetUsage())
        .setNetLimit(netLimit)
        .setTotalNetLimit(totalNetLimit)
        .setTotalNetWeight(totalNetWeight)
        .putAllAssetNetUsed(allFreeAssetNetUsage)
        .putAllAssetNetLimit(assetNetLimitMap);
    return builder.build();
  }

  public AccountResourceMessage getAccountResource(ByteString accountAddress) {
    if (accountAddress == null || accountAddress.isEmpty()) {
      return null;
    }
    AccountResourceMessage.Builder builder = AccountResourceMessage.newBuilder();
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(accountAddress.toByteArray());
    if (accountCapsule == null) {
      return null;
    }

    BandwidthProcessor processor = new BandwidthProcessor(dbManager);
    processor.updateUsage(accountCapsule);

    EnergyProcessor energyProcessor = new EnergyProcessor(dbManager);
    energyProcessor.updateUsage(accountCapsule);

    ManaProcessor manaProcessor = new ManaProcessor(dbManager);
    manaProcessor.updateUsage(accountCapsule);

    long netLimit = processor
        .calculateGlobalNetLimit(accountCapsule);
    long freeNetLimit = dbManager.getDynamicPropertiesStore().getFreeNetLimit();
    long totalNetLimit = dbManager.getDynamicPropertiesStore().getTotalNetLimit();
    long totalNetWeight = dbManager.getDynamicPropertiesStore().getTotalNetWeight();
    long energyLimit = energyProcessor
        .calculateGlobalEnergyLimit(accountCapsule);
    long totalEnergyLimit = dbManager.getDynamicPropertiesStore().getTotalEnergyCurrentLimit();
    long totalEnergyWeight = dbManager.getDynamicPropertiesStore().getTotalEnergyWeight();

    long manaLimit = dbManager.getDynamicPropertiesStore().getFreeManaLimit();

    long storageLimit = accountCapsule.getAccountResource().getStorageLimit();
    long storageUsage = accountCapsule.getAccountResource().getStorageUsage();

    Map<String, Long> assetNetLimitMap = new HashMap<>();
    Map<String, Long> allFreeAssetNetUsage;
    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      allFreeAssetNetUsage = accountCapsule.getAllFreeAssetNetUsage();
      allFreeAssetNetUsage.keySet().forEach(asset -> {
        byte[] key = ByteArray.fromString(asset);
        assetNetLimitMap
            .put(asset, dbManager.getAssetIssueStore().get(key).getFreeAssetNetLimit());
      });
    } else {
      allFreeAssetNetUsage = accountCapsule.getAllFreeAssetNetUsageV2();
      allFreeAssetNetUsage.keySet().forEach(asset -> {
        byte[] key = ByteArray.fromString(asset);
        assetNetLimitMap
            .put(asset, dbManager.getAssetIssueV2Store().get(key).getFreeAssetNetLimit());
      });
    }

    builder.setFreeNetUsed(accountCapsule.getFreeNetUsage())
        .setFreeNetLimit(freeNetLimit)
        .setNetUsed(accountCapsule.getNetUsage())
        .setNetLimit(netLimit)
        .setTotalNetLimit(totalNetLimit)
        .setTotalNetWeight(totalNetWeight)
        .setEnergyLimit(energyLimit)
        .setEnergyUsed(accountCapsule.getAccountResource().getEnergyUsage())
        .setTotalEnergyLimit(totalEnergyLimit)
        .setTotalEnergyWeight(totalEnergyWeight)
        .setManaLimit(manaLimit)
        .setManaUsed(accountCapsule.getManaUsage())
        .setStorageLimit(storageLimit)
        .setStorageUsed(storageUsage)
        .putAllAssetNetUsed(allFreeAssetNetUsage)
        .putAllAssetNetLimit(assetNetLimitMap);
    return builder.build();
  }

  public AssetIssueContract getAssetIssueByName(ByteString assetName)
      throws NonUniqueObjectException {
    if (assetName == null || assetName.isEmpty()) {
      return null;
    }

    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      // fetch from old DB, same as old logic ops
      AssetIssueCapsule assetIssueCapsule =
          dbManager.getAssetIssueStore().get(assetName.toByteArray());
      return assetIssueCapsule != null ? assetIssueCapsule.getInstance() : null;
    } else {
      // get asset issue by name from new DB
      List<AssetIssueCapsule> assetIssueCapsuleList =
          dbManager.getAssetIssueV2Store().getAllAssetIssues();
      AssetIssueList.Builder builder = AssetIssueList.newBuilder();
      assetIssueCapsuleList
          .stream()
          .filter(assetIssueCapsule -> assetIssueCapsule.getName().equals(assetName))
          .forEach(
              issueCapsule -> {
                builder.addAssetIssue(issueCapsule.getInstance());
              });

      // check count
      if (builder.getAssetIssueCount() > 1) {
        throw new NonUniqueObjectException("get more than one asset, please use getassetissuebyid");
      } else {
        // fetch from DB by assetName as id
        AssetIssueCapsule assetIssueCapsule =
            dbManager.getAssetIssueV2Store().get(assetName.toByteArray());

        if (assetIssueCapsule != null) {
          // check already fetch
          if (builder.getAssetIssueCount() > 0
              && builder.getAssetIssue(0).getId().equals(assetIssueCapsule.getInstance().getId())) {
            return assetIssueCapsule.getInstance();
          }

          builder.addAssetIssue(assetIssueCapsule.getInstance());
          // check count
          if (builder.getAssetIssueCount() > 1) {
            throw new NonUniqueObjectException(
                "get more than one asset, please use getassetissuebyid");
          }
        }
      }

      if (builder.getAssetIssueCount() > 0) {
        return builder.getAssetIssue(0);
      } else {
        return null;
      }
    }
  }

  public AssetIssueList getAssetIssueListByName(ByteString assetName) {
    if (assetName == null || assetName.isEmpty()) {
      return null;
    }

    List<AssetIssueCapsule> assetIssueCapsuleList =
        dbManager.getAssetIssueStoreFinal().getAllAssetIssues();

    AssetIssueList.Builder builder = AssetIssueList.newBuilder();
    assetIssueCapsuleList.stream()
        .filter(assetIssueCapsule -> assetIssueCapsule.getName().equals(assetName))
        .forEach(issueCapsule -> {
          builder.addAssetIssue(issueCapsule.getInstance());
        });

    return builder.build();
  }

  public AssetIssueContract getAssetIssueById(String assetId) {
    if (assetId == null || assetId.isEmpty()) {
      return null;
    }
    AssetIssueCapsule assetIssueCapsule = dbManager.getAssetIssueV2Store()
        .get(ByteArray.fromString(assetId));
    return assetIssueCapsule != null ? assetIssueCapsule.getInstance() : null;
  }

  public NumberMessage totalTransaction() {
    NumberMessage.Builder builder = NumberMessage.newBuilder()
        .setNum(dbManager.getTransactionStore().getTotalTransactions());
    return builder.build();
  }

  public NumberMessage getNextMaintenanceTime() {
    NumberMessage.Builder builder = NumberMessage.newBuilder()
        .setNum(dbManager.getDynamicPropertiesStore().getNextMaintenanceTime());
    return builder.build();
  }

  public Block getBlockById(ByteString BlockId) {
    if (Objects.isNull(BlockId)) {
      return null;
    }
    Block block = null;
    try {
      block = dbManager.getBlockStore().get(BlockId.toByteArray()).getInstance();
    } catch (StoreException e) {
    }
    return block;
  }

  public BlockList getBlocksByLimitNext(long number, long limit) {
    if (limit <= 0) {
      return null;
    }
    BlockList.Builder blockListBuilder = BlockList.newBuilder();
    dbManager.getBlockStore().getLimitNumber(number, limit).forEach(
        blockCapsule -> blockListBuilder.addBlock(blockCapsule.getInstance()));
    return blockListBuilder.build();
  }

  public BlockList getBlockByLatestNum(long getNum) {
    BlockList.Builder blockListBuilder = BlockList.newBuilder();
    dbManager.getBlockStore().getBlockByLatestNum(getNum).forEach(
        blockCapsule -> blockListBuilder.addBlock(blockCapsule.getInstance()));
    return blockListBuilder.build();
  }

  public Transaction getTransactionById(ByteString transactionId) {
    if (Objects.isNull(transactionId)) {
      return null;
    }
    TransactionCapsule transactionCapsule = null;
    try {
      transactionCapsule = dbManager.getTransactionStore()
          .get(transactionId.toByteArray());
    } catch (StoreException e) {
    }
    if (transactionCapsule != null) {
      return transactionCapsule.getInstance();
    }
    return null;
  }

  public TransactionInfo getTransactionInfoById(ByteString transactionId) {
    if (Objects.isNull(transactionId)) {
      return null;
    }
    TransactionInfoCapsule transactionInfoCapsule = null;
    try {
      transactionInfoCapsule = dbManager.getTransactionHistoryStore()
          .get(transactionId.toByteArray());
    } catch (StoreException e) {
    }
    if (transactionInfoCapsule != null) {
      return transactionInfoCapsule.getInstance();
    }
    return null;
  }

  public Proposal getProposalById(ByteString proposalId) {
    if (Objects.isNull(proposalId)) {
      return null;
    }
    ProposalCapsule proposalCapsule = null;
    try {
      proposalCapsule = dbManager.getProposalStore()
          .get(proposalId.toByteArray());
    } catch (StoreException e) {
    }
    if (proposalCapsule != null) {
      return proposalCapsule.getInstance();
    }
    return null;
  }

  public Exchange getExchangeById(ByteString exchangeId) {
    if (Objects.isNull(exchangeId)) {
      return null;
    }
    ExchangeCapsule exchangeCapsule = null;
    try {
      exchangeCapsule = dbManager.getExchangeStoreFinal().get(exchangeId.toByteArray());
    } catch (StoreException e) {
    }
    if (exchangeCapsule != null) {
      return exchangeCapsule.getInstance();
    }
    return null;
  }


  public NodeList listNodes() {
    List<NodeHandler> handlerList = nodeManager.dumpActiveNodes();

    Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();
    for (NodeHandler handler : handlerList) {
      String key = handler.getNode().getHexId() + handler.getNode().getHost();
      nodeHandlerMap.put(key, handler);
    }

    NodeList.Builder nodeListBuilder = NodeList.newBuilder();

    nodeHandlerMap.entrySet().stream()
        .forEach(v -> {
          com.wizbl.common.overlay.discover.node.Node node = v.getValue().getNode();
          nodeListBuilder.addNodes(Node.newBuilder().setAddress(
              Address.newBuilder()
                  .setHost(ByteString.copyFrom(ByteArray.fromString(node.getHost())))
                  .setPort(node.getPort())));
        });
    return nodeListBuilder.build();
  }

  public Transaction deployContract(CreateSmartContract createSmartContract,
      TransactionCapsule trxCap) {

    // do nothing, so can add some useful function later
    // trxcap contract para cacheUnpackValue has value
    return trxCap.getInstance();
  }

  public Transaction triggerContract(TriggerSmartContract triggerSmartContract,
      TransactionCapsule trxCap, Builder builder,
      Return.Builder retBuilder)
      throws ContractValidateException, ContractExeException, HeaderNotFound, VMIllegalException {

    ContractStore contractStore = dbManager.getContractStore();
    byte[] contractAddress = triggerSmartContract.getContractAddress().toByteArray();
    SmartContract.ABI abi = contractStore.getABI(contractAddress);
    if (abi == null) {
      throw new ContractValidateException("No contract or not a smart contract");
    }

    byte[] selector = getSelector(triggerSmartContract.getData().toByteArray());

    if (!isConstant(abi, selector)) {
      return trxCap.getInstance();
    } else {
      if (!Args.getInstance().isSupportConstant()) {
        throw new ContractValidateException("this node don't support constant");
      }
      DepositImpl deposit = DepositImpl.createRoot(dbManager);

      Block headBlock;
      List<BlockCapsule> blockCapsuleList = dbManager.getBlockStore().getBlockByLatestNum(1);
      if (CollectionUtils.isEmpty(blockCapsuleList)) {
        throw new HeaderNotFound("latest block not found");
      } else {
        headBlock = blockCapsuleList.get(0).getInstance();
      }

      Runtime runtime = new RuntimeImpl(trxCap.getInstance(), new BlockCapsule(headBlock), deposit,
          new ProgramInvokeFactoryImpl(), true);
      VMConfig.initVmHardFork();
      runtime.execute();
      runtime.go();
      runtime.finalization();
      // TODO exception
      if (runtime.getResult().getException() != null) {
        RuntimeException e = runtime.getResult().getException();
        logger.warn("Constant call has error {}", e.getMessage());
        throw e;
      }

      ProgramResult result = runtime.getResult();
      TransactionResultCapsule ret = new TransactionResultCapsule();

      builder.addConstantResult(ByteString.copyFrom(result.getHReturn()));
      ret.setStatus(0, code.SUCESS);
      if (StringUtils.isNoneEmpty(runtime.getRuntimeError())) {
        ret.setStatus(0, code.FAILED);
        retBuilder.setMessage(ByteString.copyFromUtf8(runtime.getRuntimeError())).build();
      }
      trxCap.setResult(ret);
      return trxCap.getInstance();
    }
  }

  public SmartContract getContract(GrpcAPI.BytesMessage bytesMessage) {
    byte[] address = bytesMessage.getValue().toByteArray();
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
    if (accountCapsule == null) {
      logger.error(
          "Get contract failed, the account is not exist or the account does not have code hash!");
      return null;
    }

    ContractCapsule contractCapsule = dbManager.getContractStore()
        .get(bytesMessage.getValue().toByteArray());
    if (Objects.nonNull(contractCapsule)) {
      return contractCapsule.getInstance();
    }
    return null;
  }

  private static byte[] getSelector(byte[] data) {
    if (data == null ||
        data.length < 4) {
      return null;
    }

    byte[] ret = new byte[4];
    System.arraycopy(data, 0, ret, 0, 4);
    return ret;
  }

  private static boolean isConstant(SmartContract.ABI abi, byte[] selector) {

    if (selector == null || selector.length != 4 || abi.getEntrysList().size() == 0) {
      return false;
    }

    for (int i = 0; i < abi.getEntrysCount(); i++) {
      ABI.Entry entry = abi.getEntrys(i);
      if (entry.getType() != ABI.Entry.EntryType.Function) {
        continue;
      }

      int inputCount = entry.getInputsCount();
      StringBuffer sb = new StringBuffer();
      sb.append(entry.getName());
      sb.append("(");
      for (int k = 0; k < inputCount; k++) {
        ABI.Entry.Param param = entry.getInputs(k);
        sb.append(param.getType());
        if (k + 1 < inputCount) {
          sb.append(",");
        }
      }
      sb.append(")");

      byte[] funcSelector = new byte[4];
      System.arraycopy(Hash.sha3(sb.toString().getBytes()), 0, funcSelector, 0, 4);
      if (Arrays.equals(funcSelector, selector)) {
        if (entry.getConstant() == true || entry.getStateMutability()
            .equals(StateMutabilityType.View)) {
          return true;
        } else {
          return false;
        }
      }
    }

    return false;
  }

  /*
  input
  offset:100,limit:10
  return
  id: 101~110
   */
  public ProposalList getPaginatedProposalList(long offset, long limit) {

    if (limit < 0 || offset < 0) {
      return null;
    }

    long latestProposalNum = dbManager.getDynamicPropertiesStore().getLatestProposalNum();
    if (latestProposalNum <= offset) {
      return null;
    }
    limit = limit > PROPOSAL_COUNT_LIMIT_MAX ? PROPOSAL_COUNT_LIMIT_MAX : limit;
    long end = offset + limit;
    end = end > latestProposalNum ? latestProposalNum : end;
    ProposalList.Builder builder = ProposalList.newBuilder();

    ImmutableList<Long> rangeList = ContiguousSet
        .create(Range.openClosed(offset, end), DiscreteDomain.longs()).asList();
    rangeList.stream().map(ProposalCapsule::calculateDbKey).map(key -> {
      try {
        return dbManager.getProposalStore().get(key);
      } catch (Exception ex) {
        return null;
      }
    }).filter(Objects::nonNull)
        .forEach(proposalCapsule -> builder.addProposals(proposalCapsule.getInstance()));
    return builder.build();
  }

  public ExchangeList getPaginatedExchangeList(long offset, long limit) {

    if (limit < 0 || offset < 0) {
      return null;
    }

    long latestExchangeNum = dbManager.getDynamicPropertiesStore().getLatestExchangeNum();
    if (latestExchangeNum <= offset) {
      return null;
    }
    limit = limit > EXCHANGE_COUNT_LIMIT_MAX ? EXCHANGE_COUNT_LIMIT_MAX : limit;
    long end = offset + limit;
    end = end > latestExchangeNum ? latestExchangeNum : end;

    ExchangeList.Builder builder = ExchangeList.newBuilder();
    ImmutableList<Long> rangeList = ContiguousSet
        .create(Range.openClosed(offset, end), DiscreteDomain.longs()).asList();
    rangeList.stream().map(ExchangeCapsule::calculateDbKey).map(key -> {
      try {
        return dbManager.getExchangeStoreFinal().get(key);
      } catch (Exception ex) {
        return null;
      }
    }).filter(Objects::nonNull)
        .forEach(exchangeCapsule -> builder.addExchanges(exchangeCapsule.getInstance()));
    return builder.build();

  }


  public BlockCapsule getBlockByLatestNumTest() {
    return dbManager.getBlockStore().getBlockByLatestNum(1).get(0);
  }

}

