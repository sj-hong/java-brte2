package com.wizbl.core.capsule;

import com.wizbl.common.runtime.config.VMConfig;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.common.utils.StringUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.db.EnergyProcessor;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.BalanceInsufficientException;
import com.wizbl.protos.Protocol.ResourceReceipt;
import com.wizbl.protos.Protocol.Transaction.Result.contractResult;

public class ReceiptCapsule {

  private ResourceReceipt receipt;

  private final Sha256Hash receiptAddress;

  public ReceiptCapsule(ResourceReceipt data, Sha256Hash receiptAddress) {
    this.receipt = data;
    this.receiptAddress = receiptAddress;
  }

  public ReceiptCapsule(Sha256Hash receiptAddress) {
    this.receipt = ResourceReceipt.newBuilder().build();
    this.receiptAddress = receiptAddress;
  }

  public void setReceipt(ResourceReceipt receipt) {
    this.receipt = receipt;
  }

  public ResourceReceipt getReceipt() {
    return this.receipt;
  }

  public Sha256Hash getReceiptAddress() {
    return this.receiptAddress;
  }

  public void setNetUsage(long netUsage) {
    this.receipt = this.receipt.toBuilder().setNetUsage(netUsage).build();
  }

  public void setNetFee(long netFee) {
    this.receipt = this.receipt.toBuilder().setNetFee(netFee).build();
  }

  public long getEnergyUsage() {
    return this.receipt.getEnergyUsage();
  }

  public long getEnergyFee() {
    return this.receipt.getEnergyFee();
  }

  public void setEnergyUsage(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setEnergyUsage(energyUsage).build();
  }

  public void setEnergyFee(long energyFee) {
    this.receipt = this.receipt.toBuilder().setEnergyFee(energyFee).build();
  }

  public long getOriginEnergyUsage() {
    return this.receipt.getOriginEnergyUsage();
  }

  public long getEnergyUsageTotal() {
    return this.receipt.getEnergyUsageTotal();
  }

  public void setOriginEnergyUsage(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setOriginEnergyUsage(energyUsage).build();
  }

  public void setEnergyUsageTotal(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setEnergyUsageTotal(energyUsage).build();
  }

  public long getNetUsage() {
    return this.receipt.getNetUsage();
  }

  public long getNetFee() {
    return this.receipt.getNetFee();
  }

  public long getManaUsage() {
    return this.receipt.getManaUsage();
  }

  public void setManaUsage(long manaUsage) {
    this.receipt = this.receipt.toBuilder().setManaUsage(manaUsage).build();
  }

  public long getManaFee() {
    return this.receipt.getManaFee();
  }

  public void setManaFee(long manaFee) {
    this.receipt = this.receipt.toBuilder().setManaFee(manaFee).build();
  }

  public long getManaUsageTotal() {
    return this.receipt.getManaUsageTotal();
  }

  public void setManaUsageTotal(long manaUsageTotal) {
    this.receipt = this.receipt.toBuilder().setManaUsageTotal(manaUsageTotal).build();
  }

  /**
   * payEnergyBill pay receipt energy bill by energy processor.
   */
  public void payEnergyBill(Manager manager, AccountCapsule origin, AccountCapsule caller,
      long percent, long originEnergyLimit, EnergyProcessor energyProcessor, long now)
      throws BalanceInsufficientException {
    if (receipt.getEnergyUsageTotal() <= 0) {
      return;
    }

    if (caller.getAddress().equals(origin.getAddress())) {
      payEnergyBill(manager, caller, receipt.getEnergyUsageTotal(), energyProcessor, now);
    } else {
      long originUsage = Math.multiplyExact(receipt.getEnergyUsageTotal(), percent) / 100;
      originUsage = getOriginUsage(manager, origin, originEnergyLimit, energyProcessor, originUsage);

      long callerUsage = receipt.getEnergyUsageTotal() - originUsage;
      energyProcessor.useEnergy(origin, originUsage, now);
      this.setOriginEnergyUsage(originUsage);
      payEnergyBill(manager, caller, callerUsage, energyProcessor, now);
    }
  }

  private long getOriginUsage(Manager manager, AccountCapsule origin,
      long originEnergyLimit,
      EnergyProcessor energyProcessor, long originUsage) {

    if (VMConfig.getEnergyLimitHardFork()) {
      return Math.min(originUsage,
          Math.min(energyProcessor.getAccountLeftEnergyFromFreeze(origin), originEnergyLimit));
    }
    return Math.min(originUsage, energyProcessor.getAccountLeftEnergyFromFreeze(origin));
  }

  private void payEnergyBill(
      Manager manager,
      AccountCapsule account,
      long usage,
      EnergyProcessor energyProcessor,
      long now) throws BalanceInsufficientException {
    long accountEnergyLeft = energyProcessor.getAccountLeftEnergyFromFreeze(account);
    if (accountEnergyLeft >= usage) {
      energyProcessor.useEnergy(account, usage, now);
      this.setEnergyUsage(usage);
    } else {
      energyProcessor.useEnergy(account, accountEnergyLeft, now);
      long sunPerEnergy = Constant.SUN_PER_ENERGY;
      long dynamicEnergyFee = manager.getDynamicPropertiesStore().getEnergyFee();
      if (dynamicEnergyFee > 0) {
        sunPerEnergy = dynamicEnergyFee;
      }
      long energyFee =
              (usage - accountEnergyLeft) * sunPerEnergy;
      this.setEnergyUsage(accountEnergyLeft);
      this.setEnergyFee(energyFee);
      long balance = account.getBalance();
      if (balance < energyFee) {
        throw new BalanceInsufficientException(
                StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
      }
      account.setBalance(balance - energyFee);

      //send to squirrel
      manager.adjustBalance(manager.getAccountStore().getSquirrel().getAddress().toByteArray(),
              energyFee);
    }

    manager.getAccountStore().put(account.getAddress().toByteArray(), account);
  }

  public static ResourceReceipt copyReceipt(ReceiptCapsule origin) {
    return origin.getReceipt().toBuilder().build();
  }

  public void setResult(contractResult success) {
    this.receipt = receipt.toBuilder().setResult(success).build();
  }

  public contractResult getResult() {
    return this.receipt.getResult();
  }
}
