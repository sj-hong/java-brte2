package com.wizbl.core.db;

import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.common.runtime.config.VMConfig;
import com.wizbl.common.utils.*;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.*;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.capsule.utils.BlockUtil;
import com.wizbl.core.config.Parameter.AdaptiveResourceLimitConstants;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.config.args.GenesisBlock;
import com.wizbl.core.db.KhaosDatabase.KhaosBlock;
import com.wizbl.core.db.api.AssetUpdateHelper;
import com.wizbl.core.db2.core.ISession;
import com.wizbl.core.db2.core.IBrte2ChainBase;
import com.wizbl.core.db2.core.SnapshotManager;
import com.wizbl.core.exception.*;
import com.wizbl.core.services.WitnessService;
import com.wizbl.core.witness.ProposalController;
import com.wizbl.core.witness.WitnessController;
import com.wizbl.protos.Protocol.AccountType;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import javafx.util.Pair;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.wizbl.core.config.Parameter.ChainConstant.SOLIDIFIED_THRESHOLD;
import static com.wizbl.core.config.Parameter.NodeConstant.MAX_TRANSACTION_PENDING;


@Slf4j
@Component
public class Manager {

    // db store
    @Autowired
    private AccountStore accountStore;
    @Autowired
    private TransactionStore transactionStore;
    @Autowired
    private BlockStore blockStore;
    @Autowired
    private WitnessStore witnessStore;
    @Autowired
    private AssetIssueStore assetIssueStore;
    @Autowired
    private AssetIssueV2Store assetIssueV2Store;
    @Autowired
    private DynamicPropertiesStore dynamicPropertiesStore;
    @Autowired
    @Getter
    private BlockIndexStore blockIndexStore;
    @Autowired
    private AccountIdIndexStore accountIdIndexStore;
    @Autowired
    private AccountIndexStore accountIndexStore;
    @Autowired
    private WitnessScheduleStore witnessScheduleStore;
    @Autowired
    private RecentBlockStore recentBlockStore;
    @Autowired
    private VotesStore votesStore;
    @Autowired
    private ProposalStore proposalStore;
    @Autowired
    private ExchangeStore exchangeStore;
    @Autowired
    private ExchangeV2Store exchangeV2Store;
    @Autowired
    private TransactionHistoryStore transactionHistoryStore;
    @Autowired
    private CodeStore codeStore;
    @Autowired
    private ContractStore contractStore;
    @Autowired
    private DelegatedResourceStore delegatedResourceStore;
    @Autowired
    private DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;
    @Autowired
    @Getter
    private StorageRowStore storageRowStore;

    // for network
    @Autowired
    private PeersStore peersStore;


    @Autowired
    private KhaosDatabase khaosDb;


    private BlockCapsule genesisBlock;
    @Getter
    @Autowired
    private RevokingDatabase revokingStore;

    @Getter
    private SessionOptional session = SessionOptional.instance();

    @Getter
    @Setter
    private boolean isSyncMode;

    @Getter
    @Setter
    private String netType;

    @Getter
    @Setter
    private WitnessService witnessService;

    @Getter
    @Setter
    private WitnessController witnessController;

    @Getter
    @Setter
    private ProposalController proposalController;

    private ExecutorService validateSignService;

    private Thread repushThread;

    private boolean isRunRepushThread = true;

    // transactionIdCache에는 (transactionId, 상태값)이 저장되며, transactionId에 해당되는 transaction의 처리여부를 확인하는데 사용되고 있다.
    // TODO - transactionIdCache에 (transactionId, 상태값)이 저장되는 시점이 정확하게 언제인지 분석이 더 필요한 상태임.
    @Getter
    private Cache<Sha256Hash, Boolean> transactionIdCache = CacheBuilder.newBuilder().maximumSize(100_000).recordStats().build();

    @Getter
    private ForkController forkController = ForkController.instance();

    public WitnessStore getWitnessStore() {
        return this.witnessStore;
    }

    public boolean needToUpdateAsset() {
        return getDynamicPropertiesStore().getTokenUpdateDone() == 0L;
    }

    public DynamicPropertiesStore getDynamicPropertiesStore() {
        return this.dynamicPropertiesStore;
    }

    public void setDynamicPropertiesStore(final DynamicPropertiesStore dynamicPropertiesStore) {
        this.dynamicPropertiesStore = dynamicPropertiesStore;
    }

    public WitnessScheduleStore getWitnessScheduleStore() {
        return this.witnessScheduleStore;
    }

    public void setWitnessScheduleStore(final WitnessScheduleStore witnessScheduleStore) {
        this.witnessScheduleStore = witnessScheduleStore;
    }


    public DelegatedResourceStore getDelegatedResourceStore() {
        return delegatedResourceStore;
    }

    public DelegatedResourceAccountIndexStore getDelegatedResourceAccountIndexStore() {
        return delegatedResourceAccountIndexStore;
    }

    public CodeStore getCodeStore() {
        return codeStore;
    }

    public ContractStore getContractStore() {
        return contractStore;
    }

    public VotesStore getVotesStore() {
        return this.votesStore;
    }

    public ProposalStore getProposalStore() {
        return this.proposalStore;
    }

    public ExchangeStore getExchangeStore() {
        return this.exchangeStore;
    }

    public ExchangeV2Store getExchangeV2Store() {
        return this.exchangeV2Store;
    }

    public ExchangeStore getExchangeStoreFinal() {
        if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
            return getExchangeStore();
        } else {
            return getExchangeV2Store();
        }
    }

    public void putExchangeCapsule(ExchangeCapsule exchangeCapsule) {
        if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
            getExchangeStore().put(exchangeCapsule.createDbKey(), exchangeCapsule);
            ExchangeCapsule exchangeCapsuleV2 = new ExchangeCapsule(exchangeCapsule.getData());
            exchangeCapsuleV2.resetTokenWithID(this);
            getExchangeV2Store().put(exchangeCapsuleV2.createDbKey(), exchangeCapsuleV2);
        } else {
            getExchangeV2Store().put(exchangeCapsule.createDbKey(), exchangeCapsule);
        }
    }

    public List<TransactionCapsule> getPendingTransactions() {
        return this.pendingTransactions;
    }

    public List<TransactionCapsule> getPoppedTransactions() {
        return this.popedTransactions;
    }

    public BlockingQueue<TransactionCapsule> getRepushTransactions() {
        return repushTransactions;
    }

    // transactions cache
    private List<TransactionCapsule> pendingTransactions;

    // transactions popped
    private List<TransactionCapsule> popedTransactions =
            Collections.synchronizedList(Lists.newArrayList());

    // the capacity is equal to Integer.MAX_VALUE default
    private BlockingQueue<TransactionCapsule> repushTransactions;

    // for test only
    public List<ByteString> getWitnesses() {
        return witnessController.getActiveWitnesses();
    }

    // for test only
    public void addWitness(final ByteString address) {
        List<ByteString> witnessAddresses = witnessController.getActiveWitnesses();
        witnessAddresses.add(address);
        witnessController.setActiveWitnesses(witnessAddresses);
    }

    public BlockCapsule getHead() throws HeaderNotFound {
        List<BlockCapsule> blocks = getBlockStore().getBlockByLatestNum(1);
        if (CollectionUtils.isNotEmpty(blocks)) {
            return blocks.get(0);
        } else {
            logger.info("Header block Not Found");
            throw new HeaderNotFound("Header block Not Found");
        }
    }

    public synchronized BlockId getHeadBlockId() {
        return new BlockId(
                getDynamicPropertiesStore().getLatestBlockHeaderHash(),
                getDynamicPropertiesStore().getLatestBlockHeaderNumber());
    }

    public long getHeadBlockNum() {
        return getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    }

    public long getHeadBlockTimeStamp() {
        return getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
    }


    public void clearAndWriteNeighbours(Set<Node> nodes) {
        this.peersStore.put("neighbours".getBytes(), nodes);
    }

    public Set<Node> readNeighbours() {
        return this.peersStore.get("neighbours".getBytes());
    }

    /**
     * Cycle thread to repush Transactions <br/>
     * BlockingQueue에 TransactionCapsule이 들어오는걸 확인하는 waiting time은 1초.
     */
    private Runnable repushLoop =
            () -> {
                while (isRunRepushThread) {
                    try {
                        if (isGeneratingBlock()) {
                            TimeUnit.MILLISECONDS.sleep(10L);
                            continue;
                        }
                        TransactionCapsule tx = this.getRepushTransactions().poll(1, TimeUnit.SECONDS);
                        if (tx != null) {
                            this.rePush(tx);
                        }
                    } catch (InterruptedException ex) {
                        logger.info(ex.getMessage());
                        Thread.currentThread().interrupt();
                    } catch (Exception ex) {
                        logger.error("unknown exception happened in repush loop", ex);
                    } catch (Throwable throwable) {
                        logger.error("unknown throwable happened in repush loop", throwable);
                    }
                }
            };

    public void stopRepushThread() {
        isRunRepushThread = false;
    }


    /**
     * Manager에서 관리하는 객체 및 필드를 초기화하는 메소드. <br/>
     * witnessController, proposalController, pendingTransactions, repushTransactions, khaosDb, forkController 초기화
     */
    @PostConstruct // @PostConstruct 어노테이션은 객체가 생성된 후에 별도의 초기화 작업을 위해 실행하는 메소드를 선언함.
    public void init() {
        revokingStore.disable();  // db에 별도의 작업이 진행되지 않도록 비활성화
        revokingStore.check();
        this.setWitnessController(WitnessController.createInstance(this));
        this.setProposalController(ProposalController.createInstance(this));
        this.pendingTransactions = Collections.synchronizedList(Lists.newArrayList());
        this.repushTransactions = new LinkedBlockingQueue<>();

        this.initGenesis();
        try {
            this.khaosDb.start(getBlockById(getDynamicPropertiesStore().getLatestBlockHeaderHash()));
        } catch (ItemNotFoundException e) {
            logger.error(
                    "Can not find Dynamic highest block from DB! \nnumber={} \nhash={}",
                    getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
                    getDynamicPropertiesStore().getLatestBlockHeaderHash());
            logger.error(
                    "Please delete database directory({}) and restart",
                    Args.getInstance().getOutputDirectory());
            System.exit(1);
        } catch (BadItemException e) {
            e.printStackTrace();
            logger.error("DB data broken!");
            logger.error(
                    "Please delete database directory({}) and restart",
                    Args.getInstance().getOutputDirectory());
            System.exit(1);
        }
        forkController.init(this);

        // Asset 정보의 갱신이 필요한 경우에는 갱신 작업을 진행.
        if (Args.getInstance().isNeedToUpdateAsset() && needToUpdateAsset()) {
            new AssetUpdateHelper(this).doWork();
        }

        revokingStore.enable();   // db가 사용가능하도록 활성화
        validateSignService = Executors.newFixedThreadPool(Args.getInstance().getValidateSignThreadNum());
        repushThread = new Thread(repushLoop);
        repushThread.start();
    }

    public BlockId getGenesisBlockId() {
        return this.genesisBlock.getBlockId();
    }

    public BlockCapsule getGenesisBlock() {
        return genesisBlock;
    }

    /**
     * init genesis block. <br/>
     * 신규 생성된 genesis block 정보를 현재 블록체인의 genesis block의 정보와 비교 후
     * 신규 genesis block의 사용 여부를 결정함.
     */
    public void initGenesis() {
        this.genesisBlock = BlockUtil.newGenesisBlockCapsule();
        // 신규 genesis block의 hash 정보가 현재 블록체인에 저장되어 있는 경우 신규 genesis block hash를 chainId로 사용
        if (this.containBlock(this.genesisBlock.getBlockId())) {
            Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
        } else {
            if (this.hasBlocks()) {
                logger.error(
                        "genesis block modify, please delete database directory({}) and restart",
                        Args.getInstance().getOutputDirectory());
                System.exit(1);
            } else {
                // 신규 genesis block 정보를 바탕으로 신규 블록체인 데이터를 생성
                logger.info("create genesis block");
                Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());

                blockStore.put(this.genesisBlock.getBlockId().getBytes(), this.genesisBlock);
                this.blockIndexStore.put(this.genesisBlock.getBlockId());

                logger.info("save block: " + this.genesisBlock);
                // init DynamicPropertiesStore
                this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
                this.dynamicPropertiesStore.saveLatestBlockHeaderHash(this.genesisBlock.getBlockId().getByteString());
                this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(this.genesisBlock.getTimeStamp());

                this.initAccount();   // Account 정보 초기화
                this.initWitness();   // Witenss 정보 초기화
                this.witnessController.initWits();
                this.khaosDb.start(genesisBlock);
                this.updateRecentBlock(genesisBlock);
            }
        }
    }

    /**
     * save account into database. <br/>
     * conf 파일의 genesis.block.assets 항목의 값을 바탕으로
     * Account 정보를 AccountStore, AccountIdIndexStore, AccountIndexStore에 저장함.
     */
    public void initAccount() {
        final Args args = Args.getInstance();
        final GenesisBlock genesisBlockArg = args.getGenesisBlock();
        genesisBlockArg.getAssets()
                .forEach(
                        account -> {
                            account.setAccountType("Normal"); // to be set in conf
                            final AccountCapsule accountCapsule =
                                    new AccountCapsule(
                                            account.getAccountName(),
                                            ByteString.copyFrom(account.getAddress()),
                                            account.getAccountType(),
                                            account.getBalance());
                            this.accountStore.put(account.getAddress(), accountCapsule);
                            this.accountIdIndexStore.put(accountCapsule);
                            this.accountIndexStore.put(accountCapsule);
                        });
    }

    /**
     * save witnesses into database. <br/>
     * conf 파일의 genesis.block.witnesses 항목을 기초로 블록체인의 witness 정보를 초기화함.
     */
    private void initWitness() {
        final Args args = Args.getInstance();
        final GenesisBlock genesisBlockArg = args.getGenesisBlock();
        genesisBlockArg.getWitnesses()
                .forEach(
                        key -> {
                            byte[] keyAddress = key.getAddress();
                            ByteString address = ByteString.copyFrom(keyAddress);

                            final AccountCapsule accountCapsule;
                            if (!this.accountStore.has(keyAddress)) {
                                accountCapsule = new AccountCapsule(ByteString.EMPTY,
                                        address, AccountType.AssetIssue, 0L);
                            } else {
                                accountCapsule = this.accountStore.getUnchecked(keyAddress);
                            }
                            accountCapsule.setIsWitness(true);
                            this.accountStore.put(keyAddress, accountCapsule);

                            final WitnessCapsule witnessCapsule =
                                    new WitnessCapsule(address, key.getVoteCount(), key.getUrl());
                            witnessCapsule.setIsJobs(true);
                            this.witnessStore.put(keyAddress, witnessCapsule);
                        });
    }

    public AccountStore getAccountStore() {
        return this.accountStore;
    }

    public void adjustBalance(byte[] accountAddress, long amount)
            throws BalanceInsufficientException {
        AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
        adjustBalance(account, amount);
    }

    /**
     * judge balance.
     */
    public void adjustBalance(AccountCapsule account, long amount)
            throws BalanceInsufficientException {

        long balance = account.getBalance();
        if (amount == 0) {
            return;
        }

        if (amount < 0 && balance < -amount) {
            throw new BalanceInsufficientException(
                    StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
        }
        account.setBalance(Math.addExact(balance, amount));
        this.getAccountStore().put(account.getAddress().toByteArray(), account);
    }


    /**
     * 계정(account)의 잔고를 amount 만큼 조정(추가)하는 메소드
     *
     * @param accountAddress
     * @param amount
     * @throws BalanceInsufficientException (amount < 0 && allowance < -amount)이면 예외 발생
     */
    public void adjustAllowance(byte[] accountAddress, long amount) throws BalanceInsufficientException {
        AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
        long allowance = account.getAllowance(); // allowance : 계정의 보유 잔고
        if (amount == 0) {
            return;
        }

        if (amount < 0 && allowance < -amount) {
            throw new BalanceInsufficientException(StringUtil.createReadableString(accountAddress) + " insufficient balance");
        }
        account.setAllowance(allowance + amount);
        this.getAccountStore().put(account.createDbKey(), account);
    }

    /**
     * TaPoS(Transaction as Proof of Stake)
     * TaPoS는 사용자가 트랜잭션에 서명 할 때 체인의 상태를 알고 있음을 증명하기 위해
     * 사용자가 블록 체인의 최상위 상태를 가져 오는 것을 보장하는 데 사용됩니다.
     * 이렇게하면 연결된 계정의 상태가 이미 변경되어 있어도 악의적인 사용자가 트랜잭션을 재생하려고하는 재생 공격과 같은
     * 특정 형태의 공격을 방지 할 수 있습니다.
     *
     * @param transactionCapsule
     * @throws TaposException
     */
    void validateTapos(TransactionCapsule transactionCapsule) throws TaposException {
        byte[] refBlockHash = transactionCapsule.getInstance().getRawData().getRefBlockHash().toByteArray();
        byte[] refBlockNumBytes = transactionCapsule.getInstance().getRawData().getRefBlockBytes().toByteArray();
        try {
            byte[] blockHash = this.recentBlockStore.get(refBlockNumBytes).getData();
            if (Arrays.equals(blockHash, refBlockHash)) {
                return;
            } else {
                String str = String.format(
                        "Tapos failed, different block hash, %s, %s , recent block %s, solid block %s head block %s",
                        ByteArray.toLong(refBlockNumBytes),
                        Hex.toHexString(refBlockHash),
                        Hex.toHexString(blockHash),
                        getSolidBlockId().getString(),
                        getHeadBlockId().getString()).toString();
                logger.info(str);
                throw new TaposException(str);

            }
        } catch (ItemNotFoundException e) {
            String str = String.
                    format("Tapos failed, block not found, ref block %s, %s , solid block %s head block %s",
                            ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
                            getSolidBlockId().getString(), getHeadBlockId().getString()).toString();
            logger.info(str);
            throw new TaposException(str);
        }
    }

    /**
     * Transaction Max Byte size 검증, Transaction의 Expiration time 검증을 진행하는 메소드
     *
     * @param transactionCapsule
     * @throws TransactionExpirationException
     * @throws TooBigTransactionException
     */
    void validateCommon(TransactionCapsule transactionCapsule)
            throws TransactionExpirationException, TooBigTransactionException {
        if (transactionCapsule.getData().length > Constant.TRANSACTION_MAX_BYTE_SIZE) {
            throw new TooBigTransactionException(
                    "too big transaction, the size is " + transactionCapsule.getData().length + " bytes");
        }
        long transactionExpiration = transactionCapsule.getExpiration();
        long headBlockTime = getHeadBlockTimeStamp();
        if (transactionExpiration <= headBlockTime ||
                transactionExpiration > headBlockTime + Constant.MAXIMUM_TIME_UNTIL_EXPIRATION) {
            throw new TransactionExpirationException(
                    "transaction expiration, transaction expiration time is " + transactionExpiration
                            + ", but headBlockTime is " + headBlockTime);
        }
    }

    /**
     * TransactionStore에 동일한 transactionId의 존재여부를 확인하는 메소드
     *
     * @param transactionCapsule
     * @throws DupTransactionException
     */
    void validateDup(TransactionCapsule transactionCapsule) throws DupTransactionException {
        if (getTransactionStore().has(transactionCapsule.getTransactionId().getBytes())) {
            logger.debug(ByteArray.toHexString(transactionCapsule.getTransactionId().getBytes()));
            throw new DupTransactionException("dup trans");
        }
    }

    /**
     * push transaction into pending.
     */
    public boolean pushTransaction(final TransactionCapsule trx)
            throws ValidateSignatureException, ContractValidateException, ContractExeException,
            AccountResourceInsufficientException, DupTransactionException, TaposException,
            TooBigTransactionException, TransactionExpirationException,
            ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {

        if (!trx.validateSignature()) {
            throw new ValidateSignatureException("trans sig validate failed");
        }

        synchronized (this) {
            if (!session.valid()) {
                session.setValue(revokingStore.buildSession());
            }

            try (ISession tmpSession = revokingStore.buildSession()) {
                processTransaction(trx, null);
                pendingTransactions.add(trx);
                tmpSession.merge();
            }
        }
        return true;
    }


    public void consumeBandwidth(TransactionCapsule trx, TransactionTrace trace)
            throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
        BandwidthProcessor processor = new BandwidthProcessor(this);
        processor.consume(trx, trace);
    }

    public void spendMana(TransactionCapsule trx, TransactionTrace trace)
            throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
        ManaProcessor processor = new ManaProcessor(this);
        processor.spend(trx, trace);
    }

    /**
     * when switch fork need erase blocks on fork branch. <br/>
     * EraseBlock은 KhaosDB와 RevokingStore에 저장된 block 정보를 삭제함.
     */
    public synchronized void eraseBlock() {
        session.reset();
        try {
            BlockCapsule oldHeadBlock = getBlockById(getDynamicPropertiesStore().getLatestBlockHeaderHash());
            logger.info("begin to erase block:" + oldHeadBlock);
            khaosDb.pop();
            revokingStore.fastPop();
            logger.info("end to erase block:" + oldHeadBlock);
            // popedTransactions에 저장된 transactions는 향후에 다시 block에 저장되어야 함.
            popedTransactions.addAll(oldHeadBlock.getTransactions());

        } catch (ItemNotFoundException | BadItemException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    public void pushVerifiedBlock(BlockCapsule block) throws ContractValidateException,
            ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
            TransactionExpirationException, TooBigTransactionException, DupTransactionException,
            TaposException, ValidateScheduleException, ReceiptCheckErrException,
            VMIllegalException, TooBigTransactionResultException, UnLinkedBlockException,
            NonCommonBlockException, BadNumberBlockException, BadBlockException {
        block.generatedByMyself = true;
        long start = System.currentTimeMillis();
        pushBlock(block);
        logger.info("push block cost:{}ms, blockNum:{}, blockHash:{}, trx count:{}",
                System.currentTimeMillis() - start,
                block.getNum(),
                block.getBlockId(),
                block.getTransactions().size());
    }

    /**
     * BlockCapsule 객체를 블록체인에 적용하는 메소드 <br/>
     * applyBlock은 block 정보를 BlockStore에 저장, BlockIndexStore에 저장, updateFork를 수행
     * @param block
     * @throws ContractValidateException
     * @throws ContractExeException
     * @throws ValidateSignatureException
     * @throws AccountResourceInsufficientException
     * @throws TransactionExpirationException
     * @throws TooBigTransactionException
     * @throws DupTransactionException
     * @throws TaposException
     * @throws ValidateScheduleException
     * @throws ReceiptCheckErrException
     * @throws VMIllegalException
     * @throws TooBigTransactionResultException
     */
    private void applyBlock(BlockCapsule block) throws ContractValidateException,
            ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
            TransactionExpirationException, TooBigTransactionException, DupTransactionException,
            TaposException, ValidateScheduleException, ReceiptCheckErrException,
            VMIllegalException, TooBigTransactionResultException {
        processBlock(block);
        this.blockStore.put(block.getBlockId().getBytes(), block);
        this.blockIndexStore.put(block.getBlockId());
        updateFork(block);
        if (System.currentTimeMillis() - block.getTimeStamp() >= 60_000) {
            revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MAX_FLUSH_COUNT);
        } else {
            revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MIN_FLUSH_COUNT);
        }
    }

    /**
     * @param newHead khaosStore에 신규로 저장된 blockCapsule 객체
     * @throws ValidateSignatureException
     * @throws ContractValidateException
     * @throws ContractExeException
     * @throws ValidateScheduleException
     * @throws AccountResourceInsufficientException
     * @throws TaposException
     * @throws TooBigTransactionException
     * @throws TooBigTransactionResultException
     * @throws DupTransactionException
     * @throws TransactionExpirationException
     * @throws NonCommonBlockException
     * @throws ReceiptCheckErrException
     * @throws VMIllegalException
     */
    private void switchFork(BlockCapsule newHead)
            throws ValidateSignatureException, ContractValidateException, ContractExeException,
            ValidateScheduleException, AccountResourceInsufficientException, TaposException,
            TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
            NonCommonBlockException, ReceiptCheckErrException,
            VMIllegalException {
        Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> binaryTree;
        try {
            binaryTree = khaosDb.getBranch(newHead.getBlockId(), getDynamicPropertiesStore().getLatestBlockHeaderHash());
        } catch (NonCommonBlockException e) {
            logger.info(
                    "there is not the most recent common ancestor, need to remove all blocks in the fork chain.");
            BlockCapsule tmp = newHead;
            while (tmp != null) {
                khaosDb.removeBlk(tmp.getBlockId());
                tmp = khaosDb.getBlock(tmp.getParentHash());
            }

            throw e;
        }
        // binaryTree.getKey()는 newHead(newBlock)에 기반한 LinkedList<KhaosBlock>이며,
        // binaryTree.getValue()는 dynamicPropertiesStore에 저장된 latestBlockHeaderhas에 기반한 LinkedList<KhaosBlock>임.
        if (CollectionUtils.isNotEmpty(binaryTree.getValue())) {
            while (!getDynamicPropertiesStore().getLatestBlockHeaderHash()
                    .equals(binaryTree.getValue().peekLast().getParentHash())) {
                //binaryTree.getValue()는 dynamicPropertiesStore의 latestBlockHeaderHash를 기준으로 khaosBlock list를 조회한 것이므로
                // dynamicPropertiesStore에 저장된 block의 정보가 잘못된 것으로 파악하고 해당 블록을 삭제함.
                eraseBlock();
            }
        }

        // newHead와 dynamicPropertiesStore의 latestBlockHeaderHash의 차이로 인해서 발생한 분기는 dynamicPropertiesStore의 블록을 삭제함으로서 분기문제를 제거함.
        if (CollectionUtils.isNotEmpty(binaryTree.getKey())) {
            // 따라서 binaryTree에서 사용되는 LinkedList<KhaosBlock>은 신규 생성된 블록에 기반한 리스트임.
            List<KhaosBlock> first = new ArrayList<>(binaryTree.getKey());
            Collections.reverse(first);
            for (KhaosBlock item : first) {
                Exception exception = null;
                // todo  process the exception carefully later
                try (ISession tmpSession = revokingStore.buildSession()) {
                    applyBlock(item.getBlk());
                    tmpSession.commit();
                } catch (AccountResourceInsufficientException
                        | ValidateSignatureException
                        | ContractValidateException
                        | ContractExeException
                        | TaposException
                        | DupTransactionException
                        | TransactionExpirationException
                        | ReceiptCheckErrException
                        | TooBigTransactionException
                        | TooBigTransactionResultException
                        | ValidateScheduleException
                        | VMIllegalException e) {
                    logger.warn(e.getMessage(), e);
                    exception = e;
                    throw e;
                } finally {
                    if (exception != null) {
                        logger.warn("switch back because exception thrown while switching forks. " + exception.getMessage(), exception);
                        first.forEach(khaosBlock -> khaosDb.removeBlk(khaosBlock.getBlk().getBlockId()));
                        khaosDb.setHead(binaryTree.getValue().peekFirst());

                        while (!getDynamicPropertiesStore().getLatestBlockHeaderHash()
                                .equals(binaryTree.getValue().peekLast().getParentHash())) {
                            eraseBlock();
                        }
                        // 이전의 first list에 의한 블록체인 연결과정에서 예외가 발생했을 때 dynamicPropertiesStore에 기반한 list로 블록체인을 복구함.
                        List<KhaosBlock> second = new ArrayList<>(binaryTree.getValue());
                        Collections.reverse(second);
                        for (KhaosBlock khaosBlock : second) {
                            // todo  process the exception carefully later
                            try (ISession tmpSession = revokingStore.buildSession()) {
                                applyBlock(khaosBlock.getBlk());
                                tmpSession.commit();
                            } catch (AccountResourceInsufficientException
                                    | ValidateSignatureException
                                    | ContractValidateException
                                    | ContractExeException
                                    | TaposException
                                    | DupTransactionException
                                    | TransactionExpirationException
                                    | TooBigTransactionException
                                    | ValidateScheduleException e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * save a block. <br/>
     * 블록 저장과정 <br/>
     * - 1. 블록의 검증(witness 검증, merkleRoot 검증) <br/>
     * - 2. block을 KhaosDB에 저장 시 블록에 대한 부모-자식 관계 설정 <br/>
     * - 3-1. newBlock의 parentBlockHash와 DynamicPropertiesStore의 LatestBlockhash의 값이 불일치하면 블록체인 분기 발생하며,
     * 발생 된 블록체인의 분기를 해소하는 작업을 진행하며, 이 과정에서 applyBlock()을 호출하면서 정당성을 지닌 블록이 메인 블록체인에 연결됨. <br/>
     * - 3-2. 분기가 발생하지 않는 경우에는 applyBlock()메소드를 호출하여 newBlock을 블록체인에 연결함.
     *
     * @param block
     * @throws ValidateSignatureException
     * @throws ContractValidateException
     * @throws ContractExeException
     * @throws UnLinkedBlockException
     * @throws ValidateScheduleException
     * @throws AccountResourceInsufficientException
     * @throws TaposException
     * @throws TooBigTransactionException
     * @throws TooBigTransactionResultException
     * @throws DupTransactionException
     * @throws TransactionExpirationException
     * @throws BadNumberBlockException
     * @throws BadBlockException
     * @throws NonCommonBlockException
     * @throws ReceiptCheckErrException
     * @throws VMIllegalException
     */
    public synchronized void pushBlock(final BlockCapsule block)
            throws ValidateSignatureException, ContractValidateException, ContractExeException,
            UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException,
            TaposException, TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
            BadNumberBlockException, BadBlockException, NonCommonBlockException,
            ReceiptCheckErrException, VMIllegalException {

        long start = System.currentTimeMillis();
        try (PendingManager pm = new PendingManager(this)) {

            if (!block.generatedByMyself) {
                if (!block.validateSignature()) {
                    logger.warn("The signature is not validated.");
                    throw new BadBlockException("The signature is not validated");
                }

                if (!block.calcMerkleRoot().equals(block.getMerkleRoot())) {
                    logger.warn(
                            "The merkle root doesn't match, Calc result is "
                                    + block.calcMerkleRoot()
                                    + " , the headers is "
                                    + block.getMerkleRoot());
                    throw new BadBlockException("The merkle hash is not validated");
                }
            }

            // KhaosDB에서 부모-자식 관계 설정 작업이 된 BlockCapsule이 반환됨.
            BlockCapsule newBlock = this.khaosDb.push(block);

            // DB don't need lower block
            if (getDynamicPropertiesStore().getLatestBlockHeaderHash() == null) {
                if (newBlock.getNum() != 0) {
                    return;
                }
            } else {
                if (newBlock.getNum() <= getDynamicPropertiesStore().getLatestBlockHeaderNumber()) {
                    return;
                }

                // switch fork
                // newBlock의 parent block hash 와 DynamicPropertiesStore에 저장된 LatestBlockHeaderHash가 일치하지 않으면 fork가 발생함.
                // fork현상을 발생시키기 위한 테스트 환경 구축이 필요
                if (!newBlock.getParentHash().equals(getDynamicPropertiesStore().getLatestBlockHeaderHash())) {
                    logger.warn(
                            "switch fork! new head num = {}, blockid = {}",
                            newBlock.getNum(),
                            newBlock.getBlockId());

                    logger.warn(
                            "******** before switchFork ******* push block: "
                                    + block.toString()
                                    + ", new block:"
                                    + newBlock.toString()
                                    + ", dynamic head num: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                                    + ", dynamic head hash: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderHash()
                                    + ", dynamic head timestamp: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                                    + ", khaosDb head: "
                                    + khaosDb.getHead()
                                    + ", khaosDb miniStore size: "
                                    + khaosDb.getMiniStore().size()
                                    + ", khaosDb unlinkMiniStore size: "
                                    + khaosDb.getMiniUnlinkedStore().size());

                    switchFork(newBlock);
                    logger.info("save block: " + newBlock);

                    logger.warn(
                            "******** after switchFork ******* push block: "
                                    + block.toString()
                                    + ", new block:"
                                    + newBlock.toString()
                                    + ", dynamic head num: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                                    + ", dynamic head hash: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderHash()
                                    + ", dynamic head timestamp: "
                                    + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                                    + ", khaosDb head: "
                                    + khaosDb.getHead()
                                    + ", khaosDb miniStore size: "
                                    + khaosDb.getMiniStore().size()
                                    + ", khaosDb unlinkMiniStore size: "
                                    + khaosDb.getMiniUnlinkedStore().size());

                    return;
                }

                try (ISession tmpSession = revokingStore.buildSession()) {
                    applyBlock(newBlock);
                    tmpSession.commit();
                } catch (Throwable throwable) {
                    logger.error(throwable.getMessage(), throwable);
                    khaosDb.removeBlk(block.getBlockId());
                    throw throwable;
                }
            }
            logger.info("save block: " + newBlock);
        }
        logger.info("pushBlock block number:{}, cost/txs:{}/{}",
                block.getNum(),
                System.currentTimeMillis() - start,
                block.getTransactions().size());
    }

    /**
     * DynamicPropertiesStore에 지정된 slot에 생성된 블록이 위치하는지 여부, LatestBlockHeaderHash, LatestBlockHeaderNumber, LatestBlockHeaderTimestamp 갱신 <br/>
     * 또한 Block 생성에 관여한 witness가 블록 생성에 실패한 토탈 카운트, revokingStore, khaosDB의 maxSize 정보 갱신을 진행함.
     *
     * @param block
     */
    public void updateDynamicProperties(BlockCapsule block) {
        long slot = 1;
        if (block.getNum() != 1) {
            slot = witnessController.getSlotAtTime(block.getTimeStamp());
        }
        for (int i = 1; i < slot; ++i) {
            if (!witnessController.getScheduledWitness(i).equals(block.getWitnessAddress())) {
                WitnessCapsule w = this.witnessStore.getUnchecked(StringUtil.createDbKey(witnessController.getScheduledWitness(i)));
                w.setTotalMissed(w.getTotalMissed() + 1);
                this.witnessStore.put(w.createDbKey(), w);
                logger.info("{} miss a block. totalMissed = {}", w.createReadableString(), w.getTotalMissed());
            }
            // slot에 블록을 저장하지 못함을 의미
            this.dynamicPropertiesStore.applyBlock(false);
        }
        this.dynamicPropertiesStore.applyBlock(true);

        if (slot <= 0) {
            logger.warn("missedBlocks [" + slot + "] is illegal");
        }

        logger.info("update head, num = {}", block.getNum());
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash(block.getBlockId().getByteString());

        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(block.getNum());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(block.getTimeStamp());
        revokingStore.setMaxSize((int) (dynamicPropertiesStore.getLatestBlockHeaderNumber()
                - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
                + 1));
        khaosDb.setMaxSize((int)
                (dynamicPropertiesStore.getLatestBlockHeaderNumber()
                        - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
                        + 1));
    }

    /**
     * Get the fork branch.
     */
    public LinkedList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash)
            throws NonCommonBlockException {
        final Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> branch =
                this.khaosDb.getBranch(
                        getDynamicPropertiesStore().getLatestBlockHeaderHash(), forkBlockHash);

        LinkedList<KhaosBlock> blockCapsules = branch.getValue();

        if (blockCapsules.isEmpty()) {
            logger.info("empty branch {}", forkBlockHash);
            return Lists.newLinkedList();
        }

        LinkedList<BlockId> result = blockCapsules.stream()
                .map(KhaosBlock::getBlk)
                .map(BlockCapsule::getBlockId)
                .collect(Collectors.toCollection(LinkedList::new));

        result.add(blockCapsules.peekLast().getBlk().getParentBlockId());

        return result;
    }

    /**
     * judge id.
     *
     * @param blockHash blockHash
     */
    public boolean containBlock(final Sha256Hash blockHash) {
        try {
            return this.khaosDb.containBlockInMiniStore(blockHash)
                    || blockStore.get(blockHash.getBytes()) != null;
        } catch (ItemNotFoundException e) {
            return false;
        } catch (BadItemException e) {
            return false;
        }
    }

    public boolean containBlockInMainChain(BlockId blockId) {
        try {
            return blockStore.get(blockId.getBytes()) != null;
        } catch (ItemNotFoundException e) {
            return false;
        } catch (BadItemException e) {
            return false;
        }
    }

    public void setBlockReference(TransactionCapsule trans) {
        byte[] headHash = getDynamicPropertiesStore().getLatestBlockHeaderHash().getBytes();
        long headNum = getDynamicPropertiesStore().getLatestBlockHeaderNumber();
        trans.setReference(headNum, headHash);
    }

    /**
     * Get a BlockCapsule by id.
     */
    public BlockCapsule getBlockById(final Sha256Hash hash)
            throws BadItemException, ItemNotFoundException {
        return this.khaosDb.containBlock(hash) ? this.khaosDb.getBlock(hash) : blockStore.get(hash.getBytes());
    }


    /**
     * judge has blocks.
     */
    public boolean hasBlocks() {
        return blockStore.iterator().hasNext() || this.khaosDb.hasData();
    }

    /**
     * Process transaction.<br/>
     *  1. TaPoS 검증 <br/>
     *  2. transaction size , expiration time 검증 <br/>
     *  3. transaction duplicate 검증 <br/>
     *  4. bandwidth(byte크기에 기반) 수수료 지불 <br/>
     *  5. VM에서 transaction 실행 및 VM 실행에 따른 수수료 정산 <br/>
     *  6. TransactionStore, TransactionHistoryStore에 transaction 데이터 저장
     */
    public boolean processTransaction(final TransactionCapsule trxCap, BlockCapsule blockCap)
            throws ValidateSignatureException, ContractValidateException, ContractExeException,
            AccountResourceInsufficientException, TransactionExpirationException, TooBigTransactionException, TooBigTransactionResultException,
            DupTransactionException, TaposException, ReceiptCheckErrException, VMIllegalException {
        if (trxCap == null) {
            return false;
        }

        // 1. TaPoS 검증
        validateTapos(trxCap);
        // 2. transaction size, expiration time 검증
        validateCommon(trxCap);

        // 모든 트랜잭션은 단 하나의 Contract type을 가저야 함을 의미함.
        if (trxCap.getInstance().getRawData().getContractList().size() != 1) {
            throw new ContractSizeNotEqualToOneException("act size should be exactly 1, this is extend feature");
        }

        // 3. transaction duplicate 검증
        validateDup(trxCap);

        if (!trxCap.validateSignature()) {
            throw new ValidateSignatureException("trans sig validate failed");
        }

        TransactionTrace trace = new TransactionTrace(trxCap, this);
        trxCap.setTrxTrace(trace);

        // 4. bandwidth(byte 크기에 기반) 수수료 지불
        consumeBandwidth(trxCap, trace);

        // 5. VM에서 transaction 실행 및 VM 실행에 따른 수수료 정산
        VMConfig.initVmHardFork(); // Hard fork 여부만 확인
        VMConfig.initAllowTvmTransferTrc10(dynamicPropertiesStore.getAllowTvmTransferTrc10());
        trace.init(blockCap);       // transaction runtime 환경 초기화
        trace.checkIsConstant();    // smart contract의 constant 항목이 존재하는지를 검증
        trace.exec();               // vm 에서 transaction 실행

        if (Objects.nonNull(blockCap)) {
            trace.setResult();
            if (!blockCap.getInstance().getBlockHeader().getWitnessSignature().isEmpty()) {
                if (trace.checkNeedRetry()) {
                    String txId = Hex.toHexString(trxCap.getTransactionId().getBytes());
                    logger.info("Retry for tx id: {}", txId);
                    trace.init(blockCap);
                    trace.checkIsConstant();
                    trace.exec();
                    trace.setResult();
                    logger.info("Retry result for tx id: {}, tx resultCode in receipt: {}",
                            txId, trace.getReceipt().getResult());
                }
                trace.check();
            }
        }

        trace.finalization();

        /*
         TODO
          spendMana의 실행 위치가 이곳이 맞는가??? VM이 실행될 때 마나가 사용되어야 하는데..
          Mana가 bandwidth와 energy를 대체하는 개념인데...
          위에서 consumeBandwidth를 사용하고 spendMana를 호출하는 것이 맞는 지 확인이 필요함.
         */
        spendMana(trxCap, trace);

        if (Objects.nonNull(blockCap)) {
            if (getDynamicPropertiesStore().supportVM()) {
                trxCap.setResult(trace.getRuntime());
            }
        }

        // 6. TransactionStore, TransactionHistoryStore에 transaction 데이터 저장
        transactionStore.put(trxCap.getTransactionId().getBytes(), trxCap);

        TransactionInfoCapsule transactionInfo = TransactionInfoCapsule.buildInstance(trxCap, blockCap, trace);

        transactionHistoryStore.put(trxCap.getTransactionId().getBytes(), transactionInfo);

        return true;
    }


    /**
     * Get the block id from the number.
     */
    public BlockId getBlockIdByNum(final long num) throws ItemNotFoundException {
        return this.blockIndexStore.get(num);
    }

    public BlockCapsule getBlockByNum(final long num) throws ItemNotFoundException, BadItemException {
        return getBlockById(getBlockIdByNum(num));
    }

    /**
     * Generate a block. <br/>
     * 1. 블록 생성 시각에 해당되는 witness인가를 검증 <br/>
     * 2. 블록 생성 시간(timestamp) 검증 <br/>
     * 3. BlockCapsule 객체 생성(BlockHeader + Transactions) <br/>
     * 4. pendingTransactions에서 transaction 추출 후 blockCapsule에 저장 <br/>
     * &emsp; 4-1. (아래의 산술식에 기초한) 특정 시간이 지나게 되면 pendingTransactions에서 transaction 추출 작업 정지 <br/>
     * &emsp; 4-2. check the block size <br/>
     * &emsp; 4-3. apply transaction <br/>
     * 5. BlockCapsule의 header에 merkleRoot 및 sign 진행 <br/>
     * 6. 신규로 생성된 블록 저장
     */
    public synchronized BlockCapsule generateBlock(
            final WitnessCapsule witnessCapsule,
            final long when,
            final byte[] privateKey,
            Boolean lastHeadBlockIsMaintenanceBefore
        ) throws ValidateSignatureException, ContractValidateException, ContractExeException, UnLinkedBlockException,
            ValidateScheduleException, AccountResourceInsufficientException {

        // 1. 블록 생성 시각에 해당되는 witness인가를 검증
        //check that the first block after the maintenance period has just been processed
        // if (lastHeadBlockIsMaintenanceBefore != lastHeadBlockIsMaintenance()) {
        if (!witnessController.validateWitnessSchedule(witnessCapsule.getAddress(), when)) {
            logger.info("It's not my turn, "
                    + "and the first block after the maintenance period has just been processed.");

            logger.info("when:{},lastHeadBlockIsMaintenanceBefore:{},lastHeadBlockIsMaintenanceAfter:{}",
                    when, lastHeadBlockIsMaintenanceBefore, lastHeadBlockIsMaintenance());

            return null;
        }
        // }

        final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
        final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();
        final Sha256Hash preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

        // 2. 블록 생성 시간(timestamp) 검증
        // judge create block time
        if (when < timestamp) {
            throw new IllegalArgumentException("generate block timestamp is invalid.");
        }

        long postponedTrxCount = 0;

        // 3. BlockCapsule 객체 생성(BlockHeader + Transactions)
        final BlockCapsule blockCapsule = new BlockCapsule(number + 1, preHash, when, witnessCapsule.getAddress());
        blockCapsule.generatedByMyself = true;
        session.reset();
        session.setValue(revokingStore.buildSession());

        // 4. pendingTransactions에서 transaction 추출 후 blockCapsule에 저장
        Iterator iterator = pendingTransactions.iterator();
        while (iterator.hasNext() || repushTransactions.size() > 0) {
            boolean fromPending = false;
            TransactionCapsule trx;
            if (iterator.hasNext()) {
                fromPending = true;
                trx = (TransactionCapsule) iterator.next();
            } else {
                trx = repushTransactions.poll();
            }

            // 4-1. (아래의 산술식에 기초한) 특정 시간이 지나게 되면 pendingTransactions에서 transaction 추출 작업 정지
            if (DateTime.now().getMillis() - when > ChainConstant.BLOCK_PRODUCED_INTERVAL * 0.5 * Args.getInstance().getBlockProducedTimeOut() / 100) {
                logger.warn("Processing transaction time exceeds the 50% producing time。");
                break;
            }

            // 4-2. check the block size
            // TODO +3은 무슨 의미인 것인가???
            if ((blockCapsule.getInstance().getSerializedSize() + trx.getSerializedSize() + 3) > ChainConstant.BLOCK_SIZE) {
                postponedTrxCount++;
                continue;
            }
            // 4-3. apply transaction
            try (ISession tmpSeesion = revokingStore.buildSession()) {
                processTransaction(trx, blockCapsule); // 개별 transaction에 대한 실행 및 각종 수수료 처리 등
                tmpSeesion.merge();
                // push into block
                blockCapsule.addTransaction(trx);
                if (fromPending) {
                    iterator.remove();
                }
            } catch (ContractExeException e) {
                logger.info("contract not processed during execute");
                logger.debug(e.getMessage(), e);
            } catch (ContractValidateException e) {
                logger.info("contract not processed during validate");
                logger.debug(e.getMessage(), e);
            } catch (TaposException e) {
                logger.info("contract not processed during TaposException");
                logger.debug(e.getMessage(), e);
            } catch (DupTransactionException e) {
                logger.info("contract not processed during DupTransactionException");
                logger.debug(e.getMessage(), e);
            } catch (TooBigTransactionException e) {
                logger.info("contract not processed during TooBigTransactionException");
                logger.debug(e.getMessage(), e);
            } catch (TooBigTransactionResultException e) {
                logger.info("contract not processed during TooBigTransactionResultException");
                logger.debug(e.getMessage(), e);
            } catch (TransactionExpirationException e) {
                logger.info("contract not processed during TransactionExpirationException");
                logger.debug(e.getMessage(), e);
            } catch (AccountResourceInsufficientException e) {
                logger.info("contract not processed during AccountResourceInsufficientException");
                logger.debug(e.getMessage(), e);
            } catch (ValidateSignatureException e) {
                logger.info("contract not processed during ValidateSignatureException");
                logger.debug(e.getMessage(), e);
            } catch (ReceiptCheckErrException e) {
                logger.info("OutOfSlotTime exception: {}", e.getMessage());
                logger.debug(e.getMessage(), e);
            } catch (VMIllegalException e) {
                logger.warn(e.getMessage(), e);
            }
        }

        session.reset();

        if (postponedTrxCount > 0) {
            logger.info("{} transactions over the block size limit", postponedTrxCount);
        }

        logger.info("postponedTrxCount[" + postponedTrxCount + "],TrxLeft[" + pendingTransactions.size() + "],repushTrxCount[" + repushTransactions.size() + "]");

        // 5. BlockCapsule의 header에 merkleRoot 및 sign 진행
        blockCapsule.setMerkleRoot();   // blockHeader에 MerkleRoot 값 저장
        blockCapsule.sign(privateKey);  // privateKey를 이용하여 block에 sign

        try {
            // 6. 신규로 생성된 블록 저장
            this.pushBlock(blockCapsule);
            return blockCapsule;
        } catch (TaposException e) {
            logger.info("contract not processed during TaposException");
        } catch (TooBigTransactionException e) {
            logger.info("contract not processed during TooBigTransactionException");
        } catch (DupTransactionException e) {
            logger.info("contract not processed during DupTransactionException");
        } catch (TransactionExpirationException e) {
            logger.info("contract not processed during TransactionExpirationException");
        } catch (BadNumberBlockException e) {
            logger.info("generate block using wrong number");
        } catch (BadBlockException e) {
            logger.info("block exception");
        } catch (NonCommonBlockException e) {
            logger.info("non common exception");
        } catch (ReceiptCheckErrException e) {
            logger.info("OutOfSlotTime exception: {}", e.getMessage());
            logger.debug(e.getMessage(), e);
        } catch (VMIllegalException e) {
            logger.warn(e.getMessage(), e);
        } catch (TooBigTransactionResultException e) {
            logger.info("contract not processed during TooBigTransactionResultException");
        }

        return null;
    }


    public TransactionStore getTransactionStore() {
        return this.transactionStore;
    }


    public TransactionHistoryStore getTransactionHistoryStore() {
        return this.transactionHistoryStore;
    }

    public BlockStore getBlockStore() {
        return this.blockStore;
    }


    /**
     * process block.<br/>
     * Block생성 진행 가운데 witness 검증, 블록에 포함된 트랜잭션에 대한 검증, mainatenanceTime에 대한 업데이트 작업,
     * DynamicPropertiesStore 데이터 갱신, Witness 정보의 갱신, Solidity block의 정보 갱신,
     * 처리된 블록 내부의 transaction에 대한 cache 작업, RecentBlock 항목 갱신 작업을 진행하는 메소드
     */
    public void processBlock(BlockCapsule block)
            throws ValidateSignatureException, ContractValidateException, ContractExeException,
            AccountResourceInsufficientException, TaposException, TooBigTransactionException,
            DupTransactionException, TransactionExpirationException, ValidateScheduleException,
            ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {
        // todo set revoking db max size.

        if (witnessService != null) {
            witnessService.processBlock(block);
        }

        // checkWitness
        if (!witnessController.validateWitnessSchedule(block)) {
            throw new ValidateScheduleException("validateWitnessSchedule error");
        }

        for (TransactionCapsule transactionCapsule : block.getTransactions()) {
            transactionCapsule.setBlockNum(block.getNum());
            if (block.generatedByMyself) {
                transactionCapsule.setVerified(true);
            }
            processTransaction(transactionCapsule, block);
        }

        boolean needMaint = needMaintenance(block.getTimeStamp());
        if (needMaint) {
            if (block.getNum() == 1) {
                this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
            } else {
                this.processMaintenance(block);
            }
        }
        if (getDynamicPropertiesStore().getAllowAdaptiveEnergy() == 1) {
            updateAdaptiveTotalEnergyLimit();
        }
        this.updateDynamicProperties(block);
        this.updateSignedWitness(block);
        this.updateLatestSolidifiedBlock();
        this.updateTransHashCache(block);
        updateMaintenanceState(needMaint);
        updateRecentBlock(block);
    }

    public void updateAdaptiveTotalEnergyLimit() {
        long totalEnergyAverageUsage = getDynamicPropertiesStore()
                .getTotalEnergyAverageUsage();
        long targetTotalEnergyLimit = getDynamicPropertiesStore().getTotalEnergyTargetLimit();
        long totalEnergyCurrentLimit = getDynamicPropertiesStore()
                .getTotalEnergyCurrentLimit();

        long result;
        if (totalEnergyAverageUsage > targetTotalEnergyLimit) {
            result = totalEnergyCurrentLimit * AdaptiveResourceLimitConstants.CONTRACT_RATE_NUMERATOR
                    / AdaptiveResourceLimitConstants.CONTRACT_RATE_DENOMINATOR;
            // logger.info(totalEnergyAverageUsage + ">" + targetTotalEnergyLimit + "\n" + result);
        } else {
            result = totalEnergyCurrentLimit * AdaptiveResourceLimitConstants.EXPAND_RATE_NUMERATOR
                    / AdaptiveResourceLimitConstants.EXPAND_RATE_DENOMINATOR;
            // logger.info(totalEnergyAverageUsage + "<" + targetTotalEnergyLimit + "\n" + result);
        }

        result = Math.min(
                Math.max(result, getDynamicPropertiesStore().getTotalEnergyLimit()),
                getDynamicPropertiesStore().getTotalEnergyLimit()
                        * AdaptiveResourceLimitConstants.LIMIT_MULTIPLIER);

        getDynamicPropertiesStore().saveTotalEnergyCurrentLimit(result);
        logger.debug(
                "adjust totalEnergyCurrentLimit, old[" + totalEnergyCurrentLimit + "], new[" + result
                        + "]");
    }

    /**
     * BlockCapsule에 저장된 TransactionCapsule 객체의 transactionId를 cache에 저장하는 메소드
     *
     * @param block
     */
    private void updateTransHashCache(BlockCapsule block) {
        for (TransactionCapsule transactionCapsule : block.getTransactions()) {
            this.transactionIdCache.put(transactionCapsule.getTransactionId(), true);
        }
    }

    /**
     * RecentBlockStore의 정보를 갱신하는 메소드 <br/>
     * TODO - Block num의 바이트가 어떤 의미인지는 확인이 필요함.
     *
     * @param block
     */
    public void updateRecentBlock(BlockCapsule block) {
        this.recentBlockStore.put(ByteArray.subArray(
                ByteArray.fromLong(block.getNum()), 6, 8),
                new BytesCapsule(ByteArray.subArray(block.getBlockId().getBytes(), 8, 16)));
    }

    /**
     * update the latest solidified block. <br/>
     * Active Witness의 최신 블록번호를 정렬한 후 solidified position에 해당하는 블록 번호를 지정한 후 DynamicPropertiesStore에 LatestSolidifiedBlockNum을 저장함.
     */
    public void updateLatestSolidifiedBlock() {
        List<Long> numbers =
                witnessController
                        .getActiveWitnesses()
                        .stream()
                        .map(address -> witnessController.getWitnesseByAddress(address).getLatestBlockNum())
                        .sorted()
                        .collect(Collectors.toList());

        long size = witnessController.getActiveWitnesses().size();
        int solidifiedPosition = (int) (size * (1 - SOLIDIFIED_THRESHOLD * 1.0 / 100));
        if (solidifiedPosition < 0) {
            logger.warn(
                    "updateLatestSolidifiedBlock error, solidifiedPosition:{},wits.size:{}",
                    solidifiedPosition,
                    size);
            return;
        }
        long latestSolidifiedBlockNum = numbers.get(solidifiedPosition);
        //if current value is less than the previous value，keep the previous value.
        if (latestSolidifiedBlockNum < getDynamicPropertiesStore().getLatestSolidifiedBlockNum()) {
            logger.warn("latestSolidifiedBlockNum = 0,LatestBlockNum:{}", numbers);
            return;
        }

        getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(latestSolidifiedBlockNum);
        logger.info("update solid block, num = {}", latestSolidifiedBlockNum);
    }

    public void updateFork(BlockCapsule block) {
        forkController.update(block);
    }

    public long getSyncBeginNumber() {
        logger.info("headNumber:" + dynamicPropertiesStore.getLatestBlockHeaderNumber());
        logger.info(
                "syncBeginNumber:"
                        + (dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size()));
        logger.info("solidBlockNumber:" + dynamicPropertiesStore.getLatestSolidifiedBlockNum());
        return dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size();
    }

    public BlockId getSolidBlockId() {
        try {
            long num = dynamicPropertiesStore.getLatestSolidifiedBlockNum();
            return getBlockIdByNum(num);
        } catch (Exception e) {
            return getGenesisBlockId();
        }
    }

    /**
     * Determine if the current time is maintenance time.
     */
    public boolean needMaintenance(long blockTime) {
        return this.dynamicPropertiesStore.getNextMaintenanceTime() <= blockTime;
    }

    /**
     * Perform maintenance.
     */
    private void processMaintenance(BlockCapsule block) {
        proposalController.processProposals();
        witnessController.updateWitness();
        this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
        forkController.reset();
    }

    /**
     * the block update signed witness. set witness who signed block the
     * 1. the latest block num     :
     * 2. pay the trx to witness.
     * 3. the latest slot num. <br/>
     *
     * @param block
     */
    public void updateSignedWitness(BlockCapsule block) {
        // TODO: add verification
        WitnessCapsule witnessCapsule =
                witnessStore.getUnchecked(block.getInstance().getBlockHeader().getRawData().getWitnessAddress().toByteArray());
        witnessCapsule.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
        witnessCapsule.setLatestBlockNum(block.getNum());
        witnessCapsule.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));

        // Update memory witness status
        // TODO - wit 변수는 어디에 사용하는 변수인가???
        WitnessCapsule wit = witnessController.getWitnesseByAddress(block.getWitnessAddress());
        if (wit != null) {
            wit.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
            wit.setLatestBlockNum(block.getNum());
            wit.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));
        }

        this.getWitnessStore().put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);

        try {
            adjustAllowance(witnessCapsule.getAddress().toByteArray(), getDynamicPropertiesStore().getWitnessPayPerBlock());
        } catch (BalanceInsufficientException e) {
            logger.warn(e.getMessage(), e);
        }

        logger.debug(
                "updateSignedWitness. witness address:{}, blockNum:{}, totalProduced:{}",
                witnessCapsule.createReadableString(),
                block.getNum(),
                witnessCapsule.getTotalProduced());
    }

    public void updateMaintenanceState(boolean needMaint) {
        if (needMaint) {
            getDynamicPropertiesStore().saveStateFlag(1);
        } else {
            getDynamicPropertiesStore().saveStateFlag(0);
        }
    }

    public boolean lastHeadBlockIsMaintenance() {
        return getDynamicPropertiesStore().getStateFlag() == 1;
    }

    // To be added
    public long getSkipSlotInMaintenance() {
        return getDynamicPropertiesStore().getMaintenanceSkipSlots();
    }

    public AssetIssueStore getAssetIssueStore() {
        return assetIssueStore;
    }

    public AssetIssueV2Store getAssetIssueV2Store() {
        return assetIssueV2Store;
    }

    public AssetIssueStore getAssetIssueStoreFinal() {
        if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
            return getAssetIssueStore();
        } else {
            return getAssetIssueV2Store();
        }
    }

    public void setAssetIssueStore(AssetIssueStore assetIssueStore) {
        this.assetIssueStore = assetIssueStore;
    }

    public void setBlockIndexStore(BlockIndexStore indexStore) {
        this.blockIndexStore = indexStore;
    }

    public AccountIdIndexStore getAccountIdIndexStore() {
        return this.accountIdIndexStore;
    }

    public void setAccountIdIndexStore(AccountIdIndexStore indexStore) {
        this.accountIdIndexStore = indexStore;
    }

    public AccountIndexStore getAccountIndexStore() {
        return this.accountIndexStore;
    }

    public void setAccountIndexStore(AccountIndexStore indexStore) {
        this.accountIndexStore = indexStore;
    }

    public void closeAllStore() {
        logger.info("******** begin to close db ********");
        closeOneStore(accountStore);
        closeOneStore(blockStore);
        closeOneStore(blockIndexStore);
        closeOneStore(accountIdIndexStore);
        closeOneStore(accountIndexStore);
        closeOneStore(witnessStore);
        closeOneStore(witnessScheduleStore);
        closeOneStore(assetIssueStore);
        closeOneStore(dynamicPropertiesStore);
        closeOneStore(transactionStore);
        closeOneStore(codeStore);
        closeOneStore(contractStore);
        closeOneStore(storageRowStore);
        closeOneStore(exchangeStore);
        closeOneStore(peersStore);
        closeOneStore(proposalStore);
        closeOneStore(recentBlockStore);
        closeOneStore(transactionHistoryStore);
        closeOneStore(votesStore);
        closeOneStore(delegatedResourceStore);
        closeOneStore(assetIssueV2Store);
        closeOneStore(exchangeV2Store);
        logger.info("******** end to close db ********");
    }

    public void closeOneStore(IBrte2ChainBase database) {
        logger.info("******** begin to close " + database.getName() + " ********");
        try {
            database.close();
        } catch (Exception e) {
            logger.info("failed to close  " + database.getName() + ". " + e);
        } finally {
            logger.info("******** end to close " + database.getName() + " ********");
        }
    }

    public boolean isTooManyPending() {
        if (getPendingTransactions().size() + getRepushTransactions().size() > MAX_TRANSACTION_PENDING) {
            return true;
        }
        return false;
    }

    public boolean isGeneratingBlock() {
        if (Args.getInstance().isWitness()) {
            return witnessController.isGeneratingBlock();
        }
        return false;
    }

    private static class ValidateSignTask implements Callable<Boolean> {

        private TransactionCapsule trx;
        private CountDownLatch countDownLatch;

        ValidateSignTask(TransactionCapsule trx, CountDownLatch countDownLatch) {
            this.trx = trx;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public Boolean call() throws ValidateSignatureException {
            try {
                trx.validateSignature();
            } catch (ValidateSignatureException e) {
                throw e;
            } finally {
                countDownLatch.countDown();
            }
            return true;
        }
    }

    /**
     * validateTransactionSign을 진행하는 메소드, CountDownLatch, Future를 이용하고는 있지만 구체적으로 이 부분이 어떻게 작동하는지는 확인이 필요함.
     * @param block
     * @throws InterruptedException
     * @throws ValidateSignatureException
     */
    public synchronized void preValidateTransactionSign(BlockCapsule block) throws InterruptedException, ValidateSignatureException {
        logger.info("PreValidate Transaction Sign, size:" + block.getTransactions().size() + ",block num:" + block.getNum());

        int transSize = block.getTransactions().size();
        CountDownLatch countDownLatch = new CountDownLatch(transSize);
        List<Future<Boolean>> futures = new ArrayList<>(transSize);

        for (TransactionCapsule transaction : block.getTransactions()) {
            Future<Boolean> future = validateSignService.submit(new ValidateSignTask(transaction, countDownLatch));
            futures.add(future);
        }
        countDownLatch.await(); // TODO -  await()메소드의 위치가 왜 여기에 오는지 모르겠음.

        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                throw new ValidateSignatureException(e.getCause().getMessage());
            }
        }
    }

    public void rePush(TransactionCapsule tx) {
        if (transactionStore.has(tx.getTransactionId().getBytes())) {
            return;
        }

        try {
            this.pushTransaction(tx);
        } catch (ValidateSignatureException e) {
            logger.debug(e.getMessage(), e);
        } catch (ContractValidateException e) {
            logger.debug(e.getMessage(), e);
        } catch (ContractExeException e) {
            logger.debug(e.getMessage(), e);
        } catch (AccountResourceInsufficientException e) {
            logger.debug(e.getMessage(), e);
        } catch (DupTransactionException e) {
            logger.debug("pending manager: dup trans", e);
        } catch (TaposException e) {
            logger.debug("pending manager: tapos exception", e);
        } catch (TooBigTransactionException e) {
            logger.debug("too big transaction");
        } catch (TransactionExpirationException e) {
            logger.debug("expiration transaction");
        } catch (ReceiptCheckErrException e) {
            logger.debug("outOfSlotTime transaction");
        } catch (VMIllegalException e) {
            logger.debug(e.getMessage(), e);
        } catch (TooBigTransactionResultException e) {
            logger.debug("too big transaction result");
        }
    }

    public void setMode(boolean mode) {
        revokingStore.setMode(mode);
    }

}
