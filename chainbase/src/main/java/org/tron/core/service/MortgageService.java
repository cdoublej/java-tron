package org.tron.core.service;

import com.google.common.math.LongMath;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Component;
import org.tron.common.entity.Dec;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DecOracleRewardCapsule;
import org.tron.core.capsule.OracleRewardCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DelegationStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.WitnessStore;
import org.tron.protos.Protocol.OracleReward;
import org.tron.protos.Protocol.Vote;

@Slf4j(topic = "mortgage")
@Component
public class MortgageService {

  @Setter
  private WitnessStore witnessStore;

  @Setter
  @Getter
  private DelegationStore delegationStore;

  @Setter
  private DynamicPropertiesStore dynamicPropertiesStore;

  @Setter
  private AccountStore accountStore;

  public void initStore(WitnessStore witnessStore, DelegationStore delegationStore,
      DynamicPropertiesStore dynamicPropertiesStore, AccountStore accountStore) {
    this.witnessStore = witnessStore;
    this.delegationStore = delegationStore;
    this.dynamicPropertiesStore = dynamicPropertiesStore;
    this.accountStore = accountStore;
  }

  public void payStandbyWitness() {
    List<WitnessCapsule> witnessCapsules = witnessStore.getAllWitnesses();
    Map<ByteString, WitnessCapsule> witnessCapsuleMap = new HashMap<>();
    List<ByteString> witnessAddressList = new ArrayList<>();
    for (WitnessCapsule witnessCapsule : witnessCapsules) {
      witnessAddressList.add(witnessCapsule.getAddress());
      witnessCapsuleMap.put(witnessCapsule.getAddress(), witnessCapsule);
    }
    witnessAddressList.sort(Comparator.comparingLong((ByteString b) -> witnessCapsuleMap.get(b).getVoteCount())
            .reversed().thenComparing(Comparator.comparingInt(ByteString::hashCode).reversed()));
    if (witnessAddressList.size() > ChainConstant.WITNESS_STANDBY_LENGTH) {
      witnessAddressList = witnessAddressList.subList(0, ChainConstant.WITNESS_STANDBY_LENGTH);
    }
    long voteSum = 0;
    long totalPay = dynamicPropertiesStore.getWitness127PayPerBlock();
    for (ByteString b : witnessAddressList) {
      voteSum += witnessCapsuleMap.get(b).getVoteCount();
    }

    if (voteSum > 0) {
      for (ByteString b : witnessAddressList) {
        double eachVotePay = (double) totalPay / voteSum;
        long pay = (long) (witnessCapsuleMap.get(b).getVoteCount() * eachVotePay);
        logger.debug("pay {} stand reward {}", Hex.toHexString(b.toByteArray()), pay);
        payReward(b.toByteArray(), pay);
      }
    }
  }

  public void payBlockReward(byte[] witnessAddress, long value) {
    logger.debug("pay {} block reward {}", Hex.toHexString(witnessAddress), value);
    payReward(witnessAddress, value);
  }

  public void payTransactionFeeReward(byte[] witnessAddress, long value) {
    logger.debug("pay {} transaction fee reward {}", Hex.toHexString(witnessAddress), value);
    payReward(witnessAddress, value);
  }

  private void payReward(byte[] witnessAddress, long value) {
    long cycle = dynamicPropertiesStore.getCurrentCycleNumber();
    int brokerage = delegationStore.getBrokerage(cycle, witnessAddress);
    double brokerageRate = (double) brokerage / 100;
    long brokerageAmount = (long) (brokerageRate * value);
    value -= brokerageAmount;
    delegationStore.addReward(cycle, witnessAddress, value);
    adjustAllowance(witnessAddress, brokerageAmount);
  }

  public void withdrawReward(byte[] address) {
    if (!dynamicPropertiesStore.allowChangeDelegation()) {
      return;
    }
    AccountCapsule accountCapsule = accountStore.get(address);
    long beginCycle = delegationStore.getBeginCycle(address);
    long endCycle = delegationStore.getEndCycle(address);
    long currentCycle = dynamicPropertiesStore.getCurrentCycleNumber();
    long reward = 0;
    OracleRewardCapsule oracleReward = new OracleRewardCapsule();
    if (beginCycle > currentCycle || accountCapsule == null) {
      return;
    }
    if (beginCycle == currentCycle) {
      AccountCapsule account = delegationStore.getAccountVote(beginCycle, address);
      if (account != null) {
        return;
      }
    }
    //withdraw the latest cycle reward
    if (beginCycle + 1 == endCycle && beginCycle < currentCycle) {
      AccountCapsule account = delegationStore.getAccountVote(beginCycle, address);
      if (account != null) {
        reward = computeReward(beginCycle, endCycle, account);
        adjustAllowance(address, reward);
        reward = 0;
        oracleReward = computeOracleReward(beginCycle, endCycle, account);
        adjustOracleAllowance(address, oracleReward);
        oracleReward = new OracleRewardCapsule();
        logger.info("latest cycle reward {},{}", beginCycle, account.getVotesList());
      }
      beginCycle += 1;
    }
    //
    endCycle = currentCycle;
    if (CollectionUtils.isEmpty(accountCapsule.getVotesList())) {
      delegationStore.setBeginCycle(address, endCycle + 1);
      return;
    }
    if (beginCycle < endCycle) {
      reward += computeReward(beginCycle, endCycle, accountCapsule);
      adjustAllowance(address, reward);
      oracleReward = oracleReward.add(computeOracleReward(beginCycle, endCycle, accountCapsule));
      adjustOracleAllowance(address, oracleReward);
    }
    delegationStore.setBeginCycle(address, endCycle);
    delegationStore.setEndCycle(address, endCycle + 1);
    delegationStore.setAccountVote(endCycle, address, accountCapsule);
    logger.info(
        "adjust {} allowance {},,oracleAllowance {}, now currentCycle {}, beginCycle {}, endCycle {}, "
            + "account vote {},", Hex.toHexString(address), reward, oracleReward, currentCycle,
        beginCycle, endCycle, accountCapsule.getVotesList());
  }

  public long queryReward(byte[] address) {
    if (!dynamicPropertiesStore.allowChangeDelegation()) {
      return 0;
    }

    AccountCapsule accountCapsule = accountStore.get(address);
    long beginCycle = delegationStore.getBeginCycle(address);
    long endCycle = delegationStore.getEndCycle(address);
    long currentCycle = dynamicPropertiesStore.getCurrentCycleNumber();
    long reward = 0;
    if (accountCapsule == null) {
      return 0;
    }
    if (beginCycle > currentCycle) {
      return accountCapsule.getAllowance();
    }
    //withdraw the latest cycle reward
    if (beginCycle + 1 == endCycle && beginCycle < currentCycle) {
      AccountCapsule account = delegationStore.getAccountVote(beginCycle, address);
      if (account != null) {
        reward = computeReward(beginCycle, endCycle, account);
      }
      beginCycle += 1;
    }
    //
    endCycle = currentCycle;
    if (CollectionUtils.isEmpty(accountCapsule.getVotesList())) {
      return reward + accountCapsule.getAllowance();
    }
    if (beginCycle < endCycle) {
      reward += computeReward(beginCycle, endCycle, accountCapsule);
    }
    return reward + accountCapsule.getAllowance();
  }

  private long computeReward(long cycle, AccountCapsule accountCapsule) {
    long reward = 0;
    for (Vote vote : accountCapsule.getVotesList()) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      long totalReward = delegationStore.getReward(cycle, srAddress);
      long totalVote = delegationStore.getWitnessVote(cycle, srAddress);
      if (totalVote == DelegationStore.REMARK || totalVote == 0) {
        continue;
      }
      long userVote = vote.getVoteCount();
      double voteRate = (double) userVote / totalVote;
      reward += voteRate * totalReward;
      logger.debug("computeReward {} {} {} {},{},{},{}", cycle,
          Hex.toHexString(accountCapsule.getAddress().toByteArray()), Hex.toHexString(srAddress),
          userVote, totalVote, totalReward, reward);
    }
    return reward;
  }

  private long computeVoteReward(long beginCycle, long endCycle, AccountCapsule accountCapsule) {
    long reward = 0;
    for (Vote vote : accountCapsule.getVotesList()) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      BigInteger beginVi = delegationStore.getWitnessVi(beginCycle - 1, srAddress);
      BigInteger endVi = delegationStore.getWitnessVi(endCycle - 1, srAddress);
      BigInteger deltaVi = endVi.subtract(beginVi);
      if (deltaVi.signum() <= 0) {
        continue;
      }
      long userVote = vote.getVoteCount();
      reward += deltaVi.multiply(BigInteger.valueOf(userVote))
              .divide(DelegationStore.DECIMAL_OF_VI_REWARD).longValue();
    }
    return reward;
  }

  private long computeShareReward(long beginCycle, long endCycle, AccountCapsule accountCapsule) {
    long reward = 0;
    for (Vote vote : accountCapsule.getVotesList()) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      BigInteger beginVi = delegationStore.getWitnessNewVi(beginCycle - 1, srAddress);
      BigInteger endVi = delegationStore.getWitnessNewVi(endCycle - 1, srAddress);
      BigInteger deltaVi = endVi.subtract(beginVi);
      if (deltaVi.signum() <= 0) {
        continue;
      }
      long userShares = vote.getShares();
      reward += deltaVi.multiply(BigInteger.valueOf(userShares))
              .divide(DelegationStore.DECIMAL_OF_VI_REWARD).longValue();
    }
    return reward;
  }

  /**
   * Compute reward from begin cycle to end cycle, which endCycle must greater than beginCycle.
   * While computing reward after new reward algorithm taking effective cycle number,
   * it will use new algorithm instead of old way.
   * @param beginCycle begin cycle (include)
   * @param endCycle end cycle (exclude)
   * @param accountCapsule account capsule
   * @return total reward
   */
  private long computeReward(long beginCycle, long endCycle, AccountCapsule accountCapsule) {
    if (beginCycle >= endCycle) {
      return 0;
    }

    long reward = 0;
    long newAlgorithmCycle = dynamicPropertiesStore.getNewRewardAlgorithmEffectiveCycle();
    long shareAlgorithmCycle = dynamicPropertiesStore.getShareRewardAlgorithmEffectiveCycle();
    if (newAlgorithmCycle >= shareAlgorithmCycle) {
      if (beginCycle < shareAlgorithmCycle) {
        long oldEndCycle = Math.min(endCycle, shareAlgorithmCycle);
        for (long cycle = beginCycle; cycle < oldEndCycle; cycle++) {
          reward += computeReward(cycle, accountCapsule);
        }
        beginCycle = oldEndCycle;
      }
    } else {
      if (beginCycle < newAlgorithmCycle) {
        long oldEndCycle = Math.min(endCycle, newAlgorithmCycle);
        for (long cycle = beginCycle; cycle < oldEndCycle; cycle++) {
          reward += computeReward(cycle, accountCapsule);
        }
        beginCycle = oldEndCycle;
      }
      if (beginCycle < shareAlgorithmCycle) {
        long oldEndCycle = Math.min(endCycle, shareAlgorithmCycle);
        reward += computeVoteReward(beginCycle, shareAlgorithmCycle, accountCapsule);
        beginCycle = oldEndCycle;
      }
    }
    if (beginCycle < endCycle) {
      reward += computeShareReward(beginCycle, endCycle, accountCapsule);
    }

    return reward;
  }

  public WitnessCapsule getWitnessByAddress(ByteString address) {
    return witnessStore.get(address.toByteArray());
  }

  public void adjustAllowance(byte[] address, long amount) {
    try {
      if (amount <= 0) {
        return;
      }
      adjustAllowance(accountStore, address, amount);
    } catch (BalanceInsufficientException e) {
      logger.error("withdrawReward error: {},{}", Hex.toHexString(address), address, e);
    }
  }

  public void adjustAllowance(AccountStore accountStore, byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = accountStore.getUnchecked(accountAddress);
    long allowance = account.getAllowance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && allowance < -amount) {
      throw new BalanceInsufficientException(
          StringUtil.createReadableString(accountAddress) + " insufficient balance");
    }
    account.setAllowance(allowance + amount);
    accountStore.put(account.createDbKey(), account);
  }

  private void sortWitness(List<ByteString> list) {
    list.sort(Comparator.comparingLong((ByteString b) -> getWitnessByAddress(b).getVoteCount())
        .reversed().thenComparing(Comparator.comparingInt(ByteString::hashCode).reversed()));
  }

  public void adjustOracleAllowance(byte[] address, OracleRewardCapsule reward) {
    if (reward.isZero()) {
      return;
    }
    AccountCapsule account = accountStore.getUnchecked(address);
    OracleRewardCapsule oracleAllowance = new OracleRewardCapsule(account.getOracleAllowance());
    OracleReward ret = oracleAllowance.add(reward).getInstance();
    account.setOracleAllowance(ret);
    accountStore.put(account.createDbKey(), account);
  }

  private OracleRewardCapsule computeOracleReward(long beginCycle, long endCycle,
                                                  AccountCapsule accountCapsule) {
    OracleRewardCapsule oracleReward = new OracleRewardCapsule();

    if (allowStableMarketOff()) {
      return oracleReward;
    }
    if (beginCycle >= endCycle) {
      return oracleReward;
    }

    long balance = 0;
    Map<String, Long> asset = new HashMap<>();

    for (Vote vote : accountCapsule.getVotesList()) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      DecOracleRewardCapsule beginVi =
          delegationStore.getWitnessOracleVi(beginCycle - 1, srAddress);
      DecOracleRewardCapsule endVi = delegationStore.getWitnessOracleVi(endCycle - 1, srAddress);
      DecOracleRewardCapsule deltaVi = endVi.sub(beginVi);
      if (deltaVi.isZero()) {
        continue;
      }
      long userVote = vote.getShares();// vote.getVoteCount();
      OracleRewardCapsule userReward = deltaVi.mul(userVote).truncateDecimal();
      balance = LongMath.checkedAdd(balance, userReward.getBalance());
      userReward.getAsset().forEach((k, v) -> asset.merge(k, v, LongMath::checkedAdd));
    }
    return new OracleRewardCapsule(balance, asset);

  }

  public OracleRewardCapsule queryOracleReward(byte[] address) {

    OracleRewardCapsule oracleReward = new OracleRewardCapsule();

    if (allowStableMarketOff()) {
      return oracleReward;
    }

    AccountCapsule accountCapsule = accountStore.get(address);
    long beginCycle = delegationStore.getBeginCycle(address);
    long endCycle = delegationStore.getEndCycle(address);
    long currentCycle = dynamicPropertiesStore.getCurrentCycleNumber();

    if (accountCapsule == null) {
      return oracleReward;
    }
    if (beginCycle > currentCycle) {
      return new OracleRewardCapsule(accountCapsule.getOracleAllowance());
    }
    //withdraw the latest cycle reward
    if (beginCycle + 1 == endCycle && beginCycle < currentCycle) {
      AccountCapsule account = delegationStore.getAccountVote(beginCycle, address);
      if (account != null) {
        oracleReward = computeOracleReward(beginCycle, endCycle, account);
      }
      beginCycle += 1;
    }
    //
    endCycle = currentCycle;
    if (CollectionUtils.isEmpty(accountCapsule.getVotesList())) {
      return oracleReward.add(new OracleRewardCapsule(accountCapsule.getOracleAllowance()));
    }
    if (beginCycle < endCycle) {
      oracleReward = oracleReward.add(computeOracleReward(beginCycle, endCycle, accountCapsule));
    }
    return oracleReward.add(new OracleRewardCapsule(accountCapsule.getOracleAllowance()));
  }

  public void payOracleReward(byte[] witnessAddress, DecOracleRewardCapsule reward) {

    if (allowStableMarketOff()) {
      return;
    }
    long cycle = dynamicPropertiesStore.getCurrentCycleNumber();
    int brokerage = delegationStore.getBrokerage(cycle, witnessAddress);
    DecOracleRewardCapsule witnessReward = reward.mul(Dec.newDecWithPrec(brokerage, 2));
    DecOracleRewardCapsule delegatedReward = reward.sub(witnessReward);
    delegationStore.addOracleReward(cycle, witnessAddress, delegatedReward);
    adjustOracleAllowance(witnessAddress, witnessReward.truncateDecimal());
    logger.info("payOracleReward: address {}, cycle {}, brokerage {}, reward {}, witness {},"
            + " delegated {}. ",
        StringUtil.encode58Check(witnessAddress), cycle, brokerage, reward,
        witnessReward.truncateDecimal(), delegatedReward);
  }

  public boolean allowStableMarketOff() {
    return !dynamicPropertiesStore.allowChangeDelegation()
        || !dynamicPropertiesStore.allowStableMarketOn();
  }
}
