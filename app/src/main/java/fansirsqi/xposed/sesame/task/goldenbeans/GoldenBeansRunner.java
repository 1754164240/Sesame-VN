package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import fansirsqi.xposed.sesame.model.ModelFields;
import fansirsqi.xposed.sesame.model.ModelGroup;
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectAndCountModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField;

import fansirsqi.xposed.sesame.entity.AlipayGoldenBeansTaskList;
import fansirsqi.xposed.sesame.entity.AlipayGoldenBeansMallItem;

import fansirsqi.xposed.sesame.util.Log;


/**
 * 金豆夺宝任务模块：只管理配置项与执行编排。
 * <p>
 * 玩法按业务域拆到独立处理器——{@link GoldenBeansTasks}（入口日常与任务列表）、
 * {@link GoldenBeansGameCenter}（乐园抽奖与游戏权益）、{@link GoldenBeansMiner}（金猫矿工）、
 * {@link GoldenBeansExchange}（两种换豆）、{@link GoldenBeansMall}（商城兑换）；
 * 两个入口（芭芭农场 / 芝麻炼金）由 {@link GoldenBeansEntry} 描述，bizType / source / sceneCode 不可混用。
 */
public class GoldenBeansRunner {

    /** 任务黑白名单初始化标记 */
    private static final String FLAG_BLACKLIST_INIT = "BlackList::initGoldenBeans";

    private BooleanModelField goldenBeansMain;
    private BooleanModelField goldenBeansAutoTask;
    private BooleanModelField goldenBeansSign;
    private BooleanModelField goldenBeansGameDraw;
    private BooleanModelField goldenBeansAutoMine;
    private BooleanModelField goldenBeansCollectReward;
    private BooleanModelField AutoGoldenBeansTaskList;
    private SelectModelField GoldenBeansTaskList;
    private BooleanModelField goldenBeansAutoManureExchange;
    private IntegerModelField goldenBeansManureExchangeLimit;
    private BooleanModelField goldenBeansAutoSesameExchange;
    private IntegerModelField goldenBeansSesameExchangeLimit;
    private BooleanModelField goldenBeansMallExchange;
    private SelectAndCountModelField GoldenBeansMallItemList;
    private IntegerModelField executeInterval;


    public String getName() {
        return "金豆夺宝";
    }


    public ModelGroup getGroup() {
        return ModelGroup.GOLDENBEANS;
    }


    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(goldenBeansMain = new BooleanModelField("goldenBeansVisitGarden", "领取营销弹窗奖励", false));
        modelFields.addField(goldenBeansAutoTask = new BooleanModelField("goldenBeansAutoTask", "自动完成任务与领奖", false));
        modelFields.addField(goldenBeansSign = new BooleanModelField("goldenBeansSign", "自动签到", false));
        modelFields.addField(goldenBeansGameDraw = new BooleanModelField("goldenBeansGameDraw", "金豆乐园奖励(抽奖/游戏)", false));
        modelFields.addField(goldenBeansAutoMine = new BooleanModelField("goldenBeansAutoMine", "自动挖矿", false));
        modelFields.addField(goldenBeansCollectReward = new BooleanModelField("goldenBeansCollectReward", "领奖后数据同步", false));
        modelFields.addField(AutoGoldenBeansTaskList = new BooleanModelField("AutoGoldenBeansTaskList", "金豆夺宝 | 自动黑名单", false));
        modelFields.addField(GoldenBeansTaskList = new SelectModelField("GoldenBeansTaskList", "金豆夺宝 | 黑名单列表", new LinkedHashSet<>(), AlipayGoldenBeansTaskList::getList));
        modelFields.addField(goldenBeansAutoManureExchange = new BooleanModelField("goldenBeansAutoManureExchange", "金豆夺宝 | 自动肥料换豆", false));
        modelFields.addField(goldenBeansManureExchangeLimit = new IntegerModelField("goldenBeansManureExchangeLimit", "金豆夺宝 | 肥料换豆单日金豆上限(0不限)", 0, 0, null));
        modelFields.addField(goldenBeansAutoSesameExchange = new BooleanModelField("goldenBeansAutoSesameExchange", "金豆夺宝 | 自动芝麻粒换豆", false));
        modelFields.addField(goldenBeansSesameExchangeLimit = new IntegerModelField("goldenBeansSesameExchangeLimit", "金豆夺宝 | 芝麻粒换豆单日金豆上限(0不限)", 0, 0, null));
        modelFields.addField(goldenBeansMallExchange = new BooleanModelField("goldenBeansMallExchange", "金豆夺宝 | 商城兑换权益", false));
        modelFields.addField(GoldenBeansMallItemList = new SelectAndCountModelField("GoldenBeansMallItemList", "金豆夺宝 | 商城可兑列表", new LinkedHashMap<>(), AlipayGoldenBeansMallItem::getList, "请填写每日兑换次数(0为不限)"));
        modelFields.addField(executeInterval = new IntegerModelField("executeInterval", "操作间隔(毫秒)", 500, 500, null));
        return modelFields;
    }


    public void run() {
        try {
            int interval = Math.max(executeInterval.getValue() != null ? executeInterval.getValue() : 500, 500);

            boolean signEnabled = GoldenBeansSupport.enabled(goldenBeansSign);
            boolean popupEnabled = GoldenBeansSupport.enabled(goldenBeansMain);
            boolean taskEnabled = GoldenBeansSupport.enabled(goldenBeansAutoTask);
            boolean gameEnabled = GoldenBeansSupport.enabled(goldenBeansGameDraw);
            boolean mineEnabled = GoldenBeansSupport.enabled(goldenBeansAutoMine);
            boolean resyncEnabled = GoldenBeansSupport.enabled(goldenBeansCollectReward);
            boolean manureEnabled = GoldenBeansSupport.enabled(goldenBeansAutoManureExchange);
            boolean sesameEnabled = GoldenBeansSupport.enabled(goldenBeansAutoSesameExchange);
            boolean mallEnabled = GoldenBeansSupport.enabled(goldenBeansMallExchange);

            GoldenBeansTasks tasks = new GoldenBeansTasks(GoldenBeansTaskList,
                    GoldenBeansSupport.enabled(AutoGoldenBeansTaskList));

            // 每日初始化任务黑白名单
            if (!GoldenBeansStatus.hasFlagToday(FLAG_BLACKLIST_INIT)) {
                if (tasks.initTaskListMap()) GoldenBeansStatus.flagToday(FLAG_BLACKLIST_INIT);
            }

            if (!signEnabled && !popupEnabled && !taskEnabled && !gameEnabled
                    && !mineEnabled && !resyncEnabled && !manureEnabled && !sesameEnabled && !mallEnabled) {
                GoldenBeansMall.run(interval, GoldenBeansMallItemList.getValue(), false);
                return;
            }

            // 两个入口分别执行：主页查询 → 签到 → 营销弹窗 → 任务列表
            boolean taskResolved = true;
            if (signEnabled || popupEnabled || taskEnabled) {
                for (GoldenBeansEntry entry : GoldenBeansEntry.ALL) {
                    if (!tasks.processEntry(entry, interval, signEnabled, popupEnabled, taskEnabled)) {
                        taskResolved = false;
                    }
                }
            }

            // 乐园奖励与金猫矿工只存在于农场入口
            boolean gameResolved = true;
            if (gameEnabled) {
                gameResolved = GoldenBeansGameCenter.run(interval);
            }
            if (mineEnabled) {
                GoldenBeansMiner.run(interval);
            }

            if (manureEnabled) {
                GoldenBeansExchange.exchangeManure(interval, dailyLimit(goldenBeansManureExchangeLimit));
            }
            if (sesameEnabled) {
                GoldenBeansExchange.exchangeSesame(interval, dailyLimit(goldenBeansSesameExchangeLimit));
            }

            // 商城可兑列表无条件同步（供配置页勾选），兑换才受开关控制
            GoldenBeansMall.run(interval, GoldenBeansMallItemList.getValue(), mallEnabled);

            if (resyncEnabled) {
                resync(interval);
            }

            // 保留未完成提示，失败不记作当日完成。
            if (!(taskResolved && gameResolved)) {
                GoldenBeansRuntime.record("金豆夺宝仍有待处理任务，下轮继续");
            }
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "run err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
        }
    }

    /** 读取配置的单日上限，非法值按 0（不限）处理 */
    private int dailyLimit(IntegerModelField field) {
        return field != null && field.getValue() != null ? Math.max(field.getValue(), 0) : 0;
    }

    /** 领奖后数据同步回查 */
    private void resync(int interval) {
        try {
            GoldenBeansSupport.pause(interval);
            for (GoldenBeansEntry entry : GoldenBeansEntry.ALL) {
                JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "JAR_INFO", "TASK_LIST"));
                if (!GoldenBeansSupport.ok(jo)) GoldenBeansRuntime.record("金豆[" + entry.alias + "]同步⚠️数据同步失败[" + GoldenBeansSupport.describe(jo) + "]");
            }
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "resync err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
        }
    }
}
