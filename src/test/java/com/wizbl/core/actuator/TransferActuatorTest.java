package com.wizbl.core.actuator;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.TransactionResultCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.protos.Contract;
import com.wizbl.protos.Protocol.AccountType;
import com.wizbl.protos.Protocol.Transaction.Result.code;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import java.io.File;
import java.math.BigDecimal;
import java.util.Date;

import static junit.framework.TestCase.fail;

// TEST CLEAR
@Slf4j
public class TransferActuatorTest {

    private static final String dbPath = "output_transfer_test";
    private static final Brte2ApplicationContext context;
    private static final String OWNER_ADDRESS;
    private static final String TO_ADDRESS;
    private static final long AMOUNT = 100000;
    private static final long OWNER_BALANCE = 999999999;
    private static final long TO_BALANCE = 100001;
    private static final String OWNER_ADDRESS_INVALID = "aaaa";
    private static final String TO_ADDRESS_INVALID = "bbb";
    private static final String OWNER_ACCOUNT_INVALID;
    private static final String OWNER_NO_BALANCE;
    private static final String To_ACCOUNT_INVALID;
    private static Manager dbManager;

    static {
        Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        OWNER_ADDRESS = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
        TO_ADDRESS = Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
        OWNER_ACCOUNT_INVALID =
                Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a3456";
        OWNER_NO_BALANCE = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a3433";
        To_ACCOUNT_INVALID =
                Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a3422";
    }

    /**
     * Init data.
     */
    @BeforeClass
    public static void init() {
        dbManager = context.getBean(Manager.class);
        //    Args.setParam(new String[]{"--output-directory", dbPath},
        //        "config-junit.conf");
        //    dbManager = new Manager();
        //    dbManager.init();
    }

    /**
     * Release resources.
     */
    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        if (FileUtil.deleteDir(new File(dbPath))) {
            logger.info("Release resources successful.");
        } else {
            logger.info("Release resources failure.");
        }
    }

    /**
     * create temp Capsule test need.
     */
    @Before
    public void createCapsule() {
        AccountCapsule ownerCapsule =
                new AccountCapsule(
                        ByteString.copyFromUtf8("owner"),
                        ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
                        AccountType.Normal,
                        OWNER_BALANCE);
        AccountCapsule toAccountCapsule =
                new AccountCapsule(
                        ByteString.copyFromUtf8("toAccount"),
                        ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
                        AccountType.Normal,
                        TO_BALANCE);
        dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
        dbManager.getAccountStore().put(toAccountCapsule.getAddress().toByteArray(), toAccountCapsule);
    }

    private Any getContract(long count) {
        long nowTime = new Date().getTime();
        return Any.pack(
                Contract.TransferContract.newBuilder()
                        .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)))
                        .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)))
                        .setAmount(count)
                        .build());
    }

    private Any getContract(long count, String owneraddress, String toaddress) {
        long nowTime = new Date().getTime();
        return Any.pack(
                Contract.TransferContract.newBuilder()
                        .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(owneraddress)))
                        .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(toaddress)))
                        .setAmount(count)
                        .build());
    }

    @Test
    public void rightTransfer() {
        TransferActuator actuator = new TransferActuator(getContract(AMOUNT), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            Assert.assertEquals(ret.getInstance().getRet(), code.SUCESS);
            AccountCapsule owner =
                    dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount =
                    dbManager.getAccountStore().get(ByteArray.fromHexString(TO_ADDRESS));

            BigDecimal amount = new BigDecimal(String.valueOf(AMOUNT));
            BigDecimal transferFeeRate = new BigDecimal(String.valueOf(ChainConstant.TRANSFER_FEE_RATE));
            BigDecimal transferFee = new BigDecimal(String.valueOf(amount.multiply(transferFeeRate)));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE - AMOUNT - transferFee.longValue());
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE + AMOUNT);
            Assert.assertTrue(true);
        } catch (ContractValidateException e) {
            Assert.assertFalse(e instanceof ContractValidateException);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }
    }

    // 현재처럼 수수료가 일정 비율로 정해져 있는 경우에는 보내는 사람의 잔고를 0으로 만드는 수치를 찾는 것은 어려운 상황임.
    // 이는 최초에 0으로 만들고자 계산한 숫자를 전송하는 경우 실제 수수료보다 더 작게 빠지기 때문에 보내는 계정에 일정금액의 돈이 남을 수밖에 없음.
    // 최대값을 구할수는 있지만 딱 떨어지는 값을 구하는 정수를 구하기는 어려움.
//    @Test
//    public void perfectTransfer() {
//        BigDecimal amount = new BigDecimal(String.valueOf(OWNER_BALANCE));
//        BigDecimal transferFeeRate = new BigDecimal(String.valueOf(ChainConstant.TRANSFER_FEE_RATE));
//        BigDecimal transferFee = new BigDecimal(String.valueOf(amount.multiply(transferFeeRate)));
//        TransferActuator actuator = new TransferActuator(getContract(OWNER_BALANCE - transferFee.longValue()), dbManager);
//        TransactionResultCapsule ret = new TransactionResultCapsule();
//        try {
//            actuator.validate();
//            actuator.execute(ret);
//            Assert.assertEquals(ret.getInstance().getRet(), code.SUCESS);
//            AccountCapsule owner = dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));
//            AccountCapsule toAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(TO_ADDRESS));
//
//            Assert.assertEquals(owner.getBalance(), 0);
//            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE + OWNER_BALANCE);
//            Assert.assertTrue(true);
//        } catch (ContractValidateException e) {
//            Assert.assertFalse(e instanceof ContractValidateException);
//        } catch (ContractExeException e) {
//            Assert.assertFalse(e instanceof ContractExeException);
//        }
//    }

    @Test
    public void moreTransfer() {
        TransferActuator actuator = new TransferActuator(getContract(OWNER_BALANCE + 1), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            Assert.assertTrue(false);
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            logger.info(e.getMessage());
            Assert.assertTrue(
                    "Validate TransferContract error, balance is not sufficient.".equals(e.getMessage()));
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }
    }


    @Test
    public void iniviateOwnerAddress() {
        TransferActuator actuator = new TransferActuator(
                getContract(10000L, OWNER_ADDRESS_INVALID, TO_ADDRESS), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            fail("Invalid ownerAddress");

        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertEquals("Invalid ownerAddress", e.getMessage());
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);

        } catch (ContractExeException e) {
            Assert.assertTrue(e instanceof ContractExeException);
        }

    }

    @Test
    public void iniviateToAddress() {
        TransferActuator actuator = new TransferActuator(
                getContract(10000L, OWNER_ADDRESS, TO_ADDRESS_INVALID), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            fail("Invalid toAddress");

        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertEquals("Invalid toAddress", e.getMessage());
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }

    }

    @Test
    public void iniviateTrx() {
        TransferActuator actuator = new TransferActuator(
                getContract(100L, OWNER_ADDRESS, OWNER_ADDRESS), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            fail("Cannot transfer trx to yourself.");

        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertEquals("Cannot transfer trx to yourself.", e.getMessage());
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }

    }

    @Test
    public void noExitOwnerAccount() {
        TransferActuator actuator = new TransferActuator(
                getContract(100L, OWNER_ACCOUNT_INVALID, TO_ADDRESS), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            fail("Validate TransferContract error, no OwnerAccount.");
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertEquals("Validate TransferContract error, no OwnerAccount.", e.getMessage());
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }

    }

    @Test
    /**
     * If to account not exit, create it.
     */
    public void noExitToAccount() {
        TransferActuator actuator = new TransferActuator(getContract(100_000_000L, OWNER_ADDRESS, To_ACCOUNT_INVALID), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            AccountCapsule noExitAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(To_ACCOUNT_INVALID));

            Assert.assertTrue(null == noExitAccount);
            actuator.validate();
            actuator.execute(ret);
            noExitAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(To_ACCOUNT_INVALID));
            Assert.assertFalse(null == noExitAccount);    //Had created.
            AccountCapsule owner = dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(TO_ADDRESS));
            BigDecimal amount = new BigDecimal(String.valueOf(100_000_000L));
            BigDecimal feeRate = new BigDecimal(String.valueOf(ChainConstant.TRANSFER_FEE_RATE));
            BigDecimal fee = new BigDecimal(String.valueOf(amount.multiply(feeRate)));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE - 100_000_000L - fee.longValue() - dbManager.getDynamicPropertiesStore().getCreateNewAccountFeeInSystemContract());
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
            noExitAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(To_ACCOUNT_INVALID));
            Assert.assertEquals(noExitAccount.getBalance(), 100_000_000L);
        } catch (ContractValidateException e) {
            Assert.assertFalse(e instanceof ContractValidateException);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        } finally {
            dbManager.getAccountStore().delete(ByteArray.fromHexString(To_ACCOUNT_INVALID));
        }
    }

    @Test
    public void zeroAmountTest() {
        TransferActuator actuator = new TransferActuator(getContract(0), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            Assert.assertTrue(false);
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertTrue("Amount must greater than 0.".equals(e.getMessage()));
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }
    }

    @Test
    public void negativeAmountTest() {
        TransferActuator actuator = new TransferActuator(getContract(-AMOUNT), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            Assert.assertTrue(false);
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertTrue("Amount must greater than 0.".equals(e.getMessage()));
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }
    }

    @Test
    public void addOverflowTest() {
        // First, increase the to balance. Else can't complete this test case.
        AccountCapsule toAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(TO_ADDRESS));
        toAccount.setBalance(Long.MAX_VALUE);
        dbManager.getAccountStore().put(ByteArray.fromHexString(TO_ADDRESS), toAccount);
        TransferActuator actuator = new TransferActuator(getContract(1), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            Assert.assertTrue(false);
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertTrue(("long overflow").equals(e.getMessage()));
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            toAccount = dbManager.getAccountStore().get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), Long.MAX_VALUE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        }
    }

    @Test
    public void insufficientFee() {
        AccountCapsule ownerCapsule =
                new AccountCapsule(
                        ByteString.copyFromUtf8("owner"),
                        ByteString.copyFrom(ByteArray.fromHexString(OWNER_NO_BALANCE)),
                        AccountType.Normal,
                        -10000L);
        AccountCapsule toAccountCapsule =
                new AccountCapsule(
                        ByteString.copyFromUtf8("toAccount"),
                        ByteString.copyFrom(ByteArray.fromHexString(To_ACCOUNT_INVALID)),
                        AccountType.Normal,
                        100L);
        dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
        dbManager.getAccountStore().put(toAccountCapsule.getAddress().toByteArray(), toAccountCapsule);

        TransferActuator actuator = new TransferActuator(
                getContract(AMOUNT, OWNER_NO_BALANCE, To_ACCOUNT_INVALID), dbManager);
        TransactionResultCapsule ret = new TransactionResultCapsule();
        try {
            actuator.validate();
            actuator.execute(ret);
            fail("Validate TransferContract error, insufficient fee.");
        } catch (ContractValidateException e) {
            Assert.assertTrue(e instanceof ContractValidateException);
            Assert.assertEquals("Validate TransferContract error, balance is not sufficient.",
                    e.getMessage());
            AccountCapsule owner = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(OWNER_ADDRESS));
            AccountCapsule toAccount = dbManager.getAccountStore()
                    .get(ByteArray.fromHexString(TO_ADDRESS));
            Assert.assertEquals(owner.getBalance(), OWNER_BALANCE);
            Assert.assertEquals(toAccount.getBalance(), TO_BALANCE);
        } catch (ContractExeException e) {
            Assert.assertFalse(e instanceof ContractExeException);
        } finally {
            dbManager.getAccountStore().delete(ByteArray.fromHexString(To_ACCOUNT_INVALID));
        }
    }

}
