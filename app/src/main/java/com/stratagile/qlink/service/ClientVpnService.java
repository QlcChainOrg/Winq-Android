package com.stratagile.qlink.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.socks.library.KLog;
import com.stratagile.qlink.api.transaction.SendBackWithTxId;
import com.stratagile.qlink.api.transaction.TransactionApi;
import com.stratagile.qlink.application.AppConfig;
import com.stratagile.qlink.constant.ConstantValue;
import com.stratagile.qlink.db.TransactionRecord;
import com.stratagile.qlink.db.TransactionRecordDao;
import com.stratagile.qlink.db.VpnEntity;
import com.stratagile.qlink.db.Wallet;
import com.stratagile.qlink.utils.CountDownTimerUtils;
import com.stratagile.qlink.utils.LogUtil;
import com.stratagile.qlink.utils.SpUtil;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by huzhipeng on 2018/2/11.
 */

public class ClientVpnService extends Service {
    private MyBroadcastReceiver myBroadcastReceiver = new MyBroadcastReceiver();
    private static CountDownTimerUtils countDownTimerUtils;
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        KLog.i("vpn计时扣费服务启动了");
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.stratagile.qlink.VPN_STATUS");
        registerReceiver(myBroadcastReceiver, filter);
        if (countDownTimerUtils == null) {
            countDownTimerUtils = CountDownTimerUtils.creatNewInstance();
            countDownTimerUtils.setMillisInFuture(Long.MAX_VALUE)
                    .setCountDownInterval(1 * 60 * 1000)
                    .setTickDelegate(new CountDownTimerUtils.TickDelegate() {
                        @Override
                        public void onTick(long pMillisUntilFinished) {
                            if (AppConfig.currentUseVpn != null && AppConfig.currentUseVpn.getIsConnected() == true) {
                                List<TransactionRecord> transactionVpnRecordList = AppConfig.getInstance().getDaoSession().getTransactionRecordDao().queryBuilder().where(TransactionRecordDao.Properties.AssetName.eq(AppConfig.currentUseVpn.getVpnName())).list();
                                if (transactionVpnRecordList.size() > 0) {
                                    Collections.sort(transactionVpnRecordList);
                                    TransactionRecord nearestRecord = transactionVpnRecordList.get(0);
                                    long lastPayTime = nearestRecord.getTimestamp();
                                    if ((Calendar.getInstance().getTimeInMillis() - lastPayTime) > 60 * 60 * 1000)//如果离上次付费超过计费周期，才扣费。vpn是按小时计费
                                    {
                                        connectToVpnRecord(AppConfig.currentUseVpn);
                                    }
                                } else {
                                    connectToVpnRecord(AppConfig.currentUseVpn);
                                }
                            }
                            //KLog.i("VPN倒计时");
                        }
                    }).start();
        } else {
            countDownTimerUtils.doOnce();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(myBroadcastReceiver);
    }

    public class MyBroadcastReceiver extends BroadcastReceiver {
        public static final String TAG = "MyBroadcastReceiver";
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.stratagile.qlink.VPN_STATUS".equals(intent.getAction())) {
                KLog.i(intent.getStringExtra("detailstatus"));
                KLog.i(intent.getStringExtra("profileuuid"));
                KLog.i(ConstantValue.P2PID);
                KLog.i(SpUtil.getString(AppConfig.getInstance(), ConstantValue.P2PID, "获取containValue失败"));
                KLog.i(AppConfig.currentVpnUseType);
                if (AppConfig.currentVpnUseType == 1) {
                    KLog.i("当前为收费模式的连接");
                }
            }
        }
    }
    public void connectToVpnRecord(VpnEntity vpnEntity) {
        LogUtil.addLog("计时器开始发起扣款申请", getClass().getSimpleName());
        KLog.i("vpn连接成功，计时器开始发起扣款申请");
        List<Wallet> walletList = AppConfig.getInstance().getDaoSession().getWalletDao().queryBuilder().list();
        Wallet wallet = walletList.get(SpUtil.getInt(AppConfig.getInstance(), ConstantValue.currentWallet, 0));
        Map<String, Object> infoMap = new HashMap<>();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String uuid1 = uuid.substring(0, 32);
        infoMap.put("recordId", uuid1);
        infoMap.put("assetName", vpnEntity.getVpnName());
        infoMap.put("type", 3);
        infoMap.put("addressFrom", wallet.getAddress());
        infoMap.put("addressTo", vpnEntity.getAddress());
        infoMap.put("qlc", vpnEntity.getQlc() + "");
        infoMap.put("fromP2pId", SpUtil.getString(AppConfig.getInstance(), ConstantValue.P2PID, ""));
        infoMap.put("toP2pId", vpnEntity.getP2pId());
        if(vpnEntity.getQlc() <=0)
        {
            return;
        }
        TransactionApi.getInstance().v2Transaction(infoMap, wallet.getAddress(), vpnEntity.getAddress(), vpnEntity.getQlc() + "", new SendBackWithTxId() {
            @Override
            public void onSuccess(String txid) {
                TransactionRecord recordSave = new TransactionRecord();
                recordSave.setTxid(txid);
                recordSave.setExChangeId(txid);
                recordSave.setAssetName(vpnEntity.getVpnName());
                recordSave.setTransactiomType(3);
                recordSave.setIsReported(false);
                recordSave.setConnectType(0);
                recordSave.setFriendNum(vpnEntity.getFriendNum());
                recordSave.setQlcCount(vpnEntity.getQlc());
                recordSave.setTimestamp(Calendar.getInstance().getTimeInMillis());
                AppConfig.getInstance().getDaoSession().getTransactionRecordDao().insert(recordSave);
            }

            @Override
            public void onFailure() {
                //ToastUtil.displayShortToast(AppConfig.getInstance().getResources().getString(R.string.deductions_failure));
            }
        });
    }
}
