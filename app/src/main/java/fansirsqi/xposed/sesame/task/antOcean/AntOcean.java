package fansirsqi.xposed.sesame.task.antOcean;

import com.fasterxml.jackson.core.type.TypeReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import fansirsqi.xposed.sesame.entity.AlipayBeach;
import fansirsqi.xposed.sesame.entity.AlipayUser;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.hook.Toast;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.hook.RequestManager;
import fansirsqi.xposed.sesame.model.ModelFields;
import fansirsqi.xposed.sesame.model.ModelGroup;
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.ChoiceModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectAndCountModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField;
import fansirsqi.xposed.sesame.util.DataStore;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskStatus;
import fansirsqi.xposed.sesame.task.antForest.AntForestRpcCall;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.BeachMap;
import fansirsqi.xposed.sesame.util.maps.IdMapManager;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.ResChecker;
import fansirsqi.xposed.sesame.util.StringUtil;
import lombok.Getter;

/**
 * @author Constanline
 * @since 2023/08/01
 */
public class AntOcean extends ModelTask {

    @Getter
    public enum ApplyAction {
        AVAILABLE(0, "可用"),
        NO_STOCK(1, "无库存"),
        ENERGY_LACK(2, "能量不足");

        private final int code;
        private final String desc;

        ApplyAction(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static ApplyAction fromString(String value) {
            for (ApplyAction action : values()) {
                if (action.name().equalsIgnoreCase(value)) {
                    return action;
                }
            }
            Log.error("ApplyAction", "Unknown applyAction: " + value);
            return null;
        }
    }

    private static final String TAG = AntOcean.class.getSimpleName();

    @Override
    public String getName() {
        return "神奇海洋";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    @Override
    public String getIcon() {
        return "AntOcean.png";
    }

    private BooleanModelField dailyOceanTask;
    private BooleanModelField aiFish;
    private BooleanModelField aiFishAutoDraw;
    private BooleanModelField aiFishAppraise;
    private BooleanSupplier executionGuard = () -> false;
    private String executionUid;
    private BooleanModelField cleanOcean;
    private ChoiceModelField cleanOceanType;
    private SelectModelField cleanOceanList;
    private BooleanModelField exchangeProp;
    private BooleanModelField usePropByType;
    private SelectAndCountModelField protectOceanList;
    private BooleanModelField PDL_task;
    private static ChoiceModelField userprotectType;

    public interface protectType {
        int DONT_PROTECT = 0;
        int PROTECT_ALL = 1;
        int PROTECT_BEACH = 2;
        String[] nickNames = {"不保护", "保护全部", "仅保护沙滩"};
    }

    private final Map<String, AtomicInteger> oceanTaskTryCount = new ConcurrentHashMap<>();

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(dailyOceanTask = new BooleanModelField("dailyOceanTask", "海洋任务", false));
        modelFields.addField(aiFish = new BooleanModelField("aiFish", "AI摸鱼", false));
        modelFields.addField(aiFishAutoDraw = new BooleanModelField("aiFishAutoDraw", "AI摸鱼 | 默认鱼自动开通画鱼", false));
        modelFields.addField(aiFishAppraise = new BooleanModelField("aiFishAppraise", "AI摸鱼 | 点赞获得能量", false));
        modelFields.addField(cleanOcean = new BooleanModelField("cleanOcean", "清理 | 开启", false));
        modelFields.addField(cleanOceanType = new ChoiceModelField("cleanOceanType", "清理 | 动作", CleanOceanType.DONT_CLEAN, CleanOceanType.nickNames));
        modelFields.addField(cleanOceanList = new SelectModelField("cleanOceanList", "清理 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(exchangeProp = new BooleanModelField("exchangeProp", "神奇海洋 | 制作万能拼图", false));
        modelFields.addField(usePropByType = new BooleanModelField("usePropByType", "神奇海洋 | 使用万能拼图", false));
        modelFields.addField(userprotectType = new ChoiceModelField("userprotectType", "保护 | 类型", protectType.DONT_PROTECT, protectType.nickNames));
        modelFields.addField(protectOceanList = new SelectAndCountModelField("protectOceanList", "保护 | 海洋列表", new LinkedHashMap<>(), AlipayBeach::getList));
        modelFields.addField(PDL_task = new BooleanModelField("PDL_task", "潘多拉任务", false));
        return modelFields;
    }

    @Override
    public void runJava() {
        executionGuard = captureExecutionGuard();
        executionUid = UserMap.INSTANCE.getCurrentUid();
        try {
            Log.record(TAG, "执行开始-" + getName());

            if (!queryOceanStatus()) {
                return;
            }
            queryHomePage();

            if (dailyOceanTask.getValue()) {
                receiveTaskAward();
            }

            if ((aiFishAutoDraw.getValue() || aiFishAppraise.getValue()) && aiFishActive()) {
                doAiFishEnhancement();
            }
            if (aiFish.getValue() && aiFishActive()) {
                doAiFish();
            }

            if (!userprotectType.getValue().equals(protectType.DONT_PROTECT)) {
                protectOcean();
            }

            if (exchangeProp.getValue()) {
                exchangeProp();
            }
            if (usePropByType.getValue()) {
                usePropByType();
            }

            if (PDL_task.getValue()) {
                doOceanPDLTask();
            }

        } catch (Throwable t) {
            Log.printStackTrace(TAG,"start.run err:", t);
        } finally {
            Log.record(TAG, "执行结束-" + getName());
        }
    }

    private void doAiFish() {
        try {
            AiFishRunResult result = new AiFishRunner(new AiFishGateway() {
                @Override public boolean active() { return aiFishActive(); }
                @Override
                public String queryStatus() {
                    return aiFishActive() ? AntOceanRpcCall.aiFishStatus() : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public String queryHome() {
                    return aiFishActive() ? AntOceanRpcCall.aiFishHomepage() : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public String listTasks(String sceneCode) {
                    return aiFishActive() ? AntOceanRpcCall.aiFishListTasks(sceneCode) : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public String finishTask(String sceneCode, String taskType) {
                    return aiFishActive() ? AntOceanRpcCall.aiFishFinishTask(sceneCode, taskType) : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public String receiveTaskAward(String sceneCode, String taskType) {
                    return aiFishActive() ? AntOceanRpcCall.aiFishReceiveTaskAward(sceneCode, taskType) : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public boolean hasCompletedToday(String taskType) {
                    return Status.hasFlagToday("antOcean::aiFish::" + taskType);
                }

                @Override
                public void markCompletedToday(String taskType) {
                    Status.setFlagToday("antOcean::aiFish::" + taskType);
                }

                @Override
                public String rescueFish() {
                    return aiFishActive() ? AntOceanRpcCall.aiFishRescue() : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public String touchFish() {
                    return aiFishActive() ? AntOceanRpcCall.aiFishTouch() : RequestManager.EMPTY_RPC_RESPONSE;
                }

                @Override
                public void waitMillis(long millis) {
                    GlobalThreadPools.sleepCompat(millis);
                }
            }).run();
            for (String event : result.getEvents()) {
                Log.forest("神奇海洋🌊[" + event + "]");
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "AI摸鱼执行异常:", t);
        }
    }

    private boolean aiFishActive() {
        return executionGuard.getAsBoolean() && !Thread.currentThread().isInterrupted()
                && !ApplicationHook.offline && !RequestManager.isVerificationPaused()
                && executionUid != null && !executionUid.isEmpty()
                && executionUid.equals(UserMap.INSTANCE.getCurrentUid());
    }

    private void doAiFishEnhancement() {
        new AiFishEnhancement(new AiFishEnhancement.Gateway() {
            public boolean active() { return aiFishActive(); }
            public String home() { return aiFishActive() ? AntOceanRpcCall.aiFishHomepage() : RequestManager.EMPTY_RPC_RESPONSE; }
            public String status() { return aiFishActive() ? AntOceanRpcCall.aiFishStatus() : RequestManager.EMPTY_RPC_RESPONSE; }
            public String draw() { return aiFishActive() ? AntOceanRpcCall.aiFishDraw() : RequestManager.EMPTY_RPC_RESPONSE; }
            public String list() { return aiFishActive() ? AntOceanRpcCall.aiFishAppraiseList() : RequestManager.EMPTY_RPC_RESPONSE; }
            public String like(String image, String season, String user) { return aiFishActive() ? AntOceanRpcCall.aiFishLike(image, season, user) : RequestManager.EMPTY_RPC_RESPONSE; }
            public boolean limitedToday() { return Status.hasFlagToday("antOcean::aiFish::appraiseLimit"); }
            public void markLimited() { if (active()) Status.setFlagToday("antOcean::aiFish::appraiseLimit"); }
            public void waitMillis(long millis) { GlobalThreadPools.sleepCompat(millis); }
            public void report(String message) { Log.forest("神奇海洋🐟" + message); }
        }).run(aiFishAutoDraw.getValue(), aiFishAppraise.getValue());
    }

    public static void initBeach() {
        try {
            String response = AntOceanRpcCall.queryCultivationList();
            JSONObject jsonResponse = new JSONObject(response);
            if (ResChecker.checkRes(TAG + "查询种植列表失败:", jsonResponse)) {
                JSONArray cultivationList = jsonResponse.optJSONArray("cultivationItemVOList");
                if (cultivationList != null) {
                    for (int i = 0; i < cultivationList.length(); i++) {
                        JSONObject item = cultivationList.getJSONObject(i);
                        String templateSubType = item.getString("templateSubType");
                        String actionStr = item.getString("applyAction");
                        ApplyAction action = ApplyAction.fromString(actionStr);
                        assert action != null;
                        if (action.equals(ApplyAction.AVAILABLE)) {
                            String templateCode = item.getString("templateCode");
                            String cultivationName = item.getString("cultivationName");
                            int energy = item.getInt("energy");
                            switch (userprotectType.getValue()) {
                                case protectType.PROTECT_ALL:
                                    IdMapManager.getInstance(BeachMap.class).add(templateCode, cultivationName + "(" + energy + "g)");
                                    break;
                                case protectType.PROTECT_BEACH:
                                    if (!templateSubType.equals("BEACH")) {
                                        IdMapManager.getInstance(BeachMap.class).add(templateCode, cultivationName + "(" + energy + "g)");
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                    Log.record(TAG, "初始化沙滩数据成功。");
                }
                IdMapManager.getInstance(BeachMap.class).save();
            } else {
                Log.error(TAG,"initBeach"+jsonResponse.optString("resultDesc", "未知错误"));
            }
        } catch (JSONException e) {
            Log.printStackTrace(TAG, "JSON 解析错误：", e);
            IdMapManager.getInstance(BeachMap.class).load();
        } catch (Exception e) {
            Log.printStackTrace(TAG, "初始化沙滩任务时出错", e);
            IdMapManager.getInstance(BeachMap.class).load();
        }
    }

    private Boolean queryOceanStatus() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanStatus());
            if (ResChecker.checkRes(TAG, jo)) {
                if (!jo.getBoolean("opened")) {
                    getEnableField().setValue(false);
                    Log.record("请先开启神奇海洋,并完成引导教程");
                    return false;
                }
                initBeach();
                return true;
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryOceanStatus err:",t);
        }
        return false;
    }
    private void queryHomePage() {
        try {
            JSONObject joHomePage = new JSONObject(AntOceanRpcCall.queryHomePage());
            if (ResChecker.checkRes(TAG + "查询海洋主页失败:", joHomePage)) {
                if (joHomePage.has("bubbleVOList")) {
                    collectEnergy(joHomePage.getJSONArray("bubbleVOList"));
                }
                JSONObject userInfoVO = joHomePage.getJSONObject("userInfoVO");
                int rubbishNumber = userInfoVO.optInt("rubbishNumber", 0);
                String userId = userInfoVO.getString("userId");
                cleanOcean(userId, rubbishNumber);
                JSONObject ipVO = userInfoVO.optJSONObject("ipVO");
                if (ipVO != null) {
                    int surprisePieceNum = ipVO.optInt("surprisePieceNum", 0);
                    if (surprisePieceNum > 0) {
                        ipOpenSurprise();
                    }
                }

                querySeaAreaDetailList();
                queryMiscInfo();
                queryReplicaHome();
                queryUserRanking();

            } else {
                Log.error(TAG, joHomePage.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryHomePage err:",t);
        }
    }

    private void queryMiscInfo() {
        try {
            String s = AntOceanRpcCall.queryMiscInfo();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋杂项信息失败:", jo)) {
                JSONObject miscHandlerVOMap = jo.getJSONObject("miscHandlerVOMap");
                JSONObject homeTipsRefresh = miscHandlerVOMap.getJSONObject("HOME_TIPS_REFRESH");
                if (homeTipsRefresh.optBoolean("fishCanBeCombined") || homeTipsRefresh.optBoolean("canBeRepaired")) {
                    querySeaAreaDetailList();
                }
                switchOceanChapter();
            } else {
                Log.error(TAG, "查询海洋杂项信息失败"+jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG,  "queryMiscInfo err:",t);
        }
    }

    private static void collectEnergy(JSONArray bubbleVOList) {
        try {
            for (int i = 0; i < bubbleVOList.length(); i++) {
                JSONObject bubble = bubbleVOList.getJSONObject(i);
                if (!"ocean".equals(bubble.getString("channel"))) {
                    continue;
                }
                if ("AVAILABLE".equals(bubble.getString("collectStatus"))) {
                    long bubbleId = bubble.getLong("id");
                    String userId = bubble.getString("userId");
                    String s = AntForestRpcCall.collectEnergy("", userId, bubbleId);
                    JSONObject jo = new JSONObject(s);
                    if (ResChecker.checkRes(TAG + "收取海洋能量失败:", jo)) {
                        JSONArray retBubbles = jo.optJSONArray("bubbles");
                        if (retBubbles != null) {
                            for (int j = 0; j < retBubbles.length(); j++) {
                                JSONObject retBubble = retBubbles.optJSONObject(j);
                                if (retBubble != null) {
                                    int collectedEnergy = retBubble.getInt("collectedEnergy");
                                    Log.forest("神奇海洋🌊收取[" + UserMap.getMaskName(userId) + "]#" + collectedEnergy + "g");
                                    Toast.INSTANCE.show("海洋能量🌊收取[" + UserMap.getMaskName(userId) + "]#" + collectedEnergy + "g");
                                }
                            }
                        }
                    } else {
                        Log.error(TAG, jo.getString("resultDesc"));
                    }
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryHomePage err:", t);
        }
    }

    private static void cleanOcean(String userId, int rubbishNumber) {
        try {
            for (int i = 0; i < rubbishNumber; i++) {
                String s = AntOceanRpcCall.cleanOcean(userId);
                JSONObject jo = new JSONObject(s);
                if (ResChecker.checkRes(TAG + "清理海洋失败:", jo)) {
                    JSONArray cleanRewardVOS = jo.getJSONArray("cleanRewardVOS");
                    checkReward(cleanRewardVOS);
                    Log.forest("神奇海洋🌊[清理:" + UserMap.getMaskName(userId) + "海域]");
                } else {
                    Log.error(TAG, jo.getString("resultDesc"));
                }
            }
        } catch (Throwable t) {

            Log.printStackTrace(TAG, "cleanOcean err:", t);
        }
    }

    private static void ipOpenSurprise() {
        try {
            String s = AntOceanRpcCall.ipOpenSurprise();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "开启海洋惊喜失败:", jo)) {
                JSONArray rewardVOS = jo.getJSONArray("surpriseRewardVOS");
                checkReward(rewardVOS);
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "ipOpenSurprise err:", t);
        }
    }

    private static void checkAndCreateExtraCollect() {
        try {
            String s = AntOceanRpcCall.querySeaAreaDetailList();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "复查海洋区域详情:", jo)) {
                if (jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false)) {
                    String availableCode = jo.optString("awardSeaAreaCode", "");
                    Log.record(TAG, "发现海域[" + availableCode + "]限时挑战已就绪！正在接取...");

                    String createRet = AntOceanRpcCall.createSeaAreaExtraCollect();
                    if (ResChecker.checkRes(TAG + "接取限时挑战:", new JSONObject(createRet))) {
                        Log.forest("限时挑战🌊接取成功");
                    }
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    private static void combineFish(String fishId, String logType) {
        try {
            String s = AntOceanRpcCall.combineFish(fishId);
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "合成海洋鱼类失败:", jo)) {
                JSONObject fishDetailVO = jo.getJSONObject("fishDetailVO");
                String name = fishDetailVO.getString("name");

                if ("EXTRA_COLLECT".equals(logType)) {
                    Log.forest("限时挑战🌊[" + name + "]合成成功");
                } else {
                    Log.forest("神奇海洋🌊[" + name + "]合成成功");
                }
                checkAndCreateExtraCollect();
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG,"combineFish err:", t);
        }
    }

    private static void checkReward(JSONArray rewards) {
        try {
            for (int i = 0; i < rewards.length(); i++) {
                JSONObject reward = rewards.getJSONObject(i);
                String name = reward.getString("name");
                JSONArray attachReward = reward.getJSONArray("attachRewardBOList");
                if (attachReward.length() > 0) {
                    Log.forest("神奇海洋🌊[获得:" + name + "碎片]");
                    boolean canCombine = true;
                    for (int j = 0; j < attachReward.length(); j++) {
                        JSONObject detail = attachReward.getJSONObject(j);
                        if (detail.optInt("count", 0) == 0) {
                            canCombine = false;
                            break;
                        }
                    }
                    if (canCombine && reward.optBoolean("unlock", false)) {
                        String fishId = reward.getString("id");
                        combineFish(fishId, "");
                    }
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG,  "checkReward err:",t);
        }
    }

    private static void collectReplicaAsset(int canCollectAssetNum) {
        try {
            for (int i = 0; i < canCollectAssetNum; i++) {
                String s = AntOceanRpcCall.collectReplicaAsset();
                JSONObject jo = new JSONObject(s);
                if (ResChecker.checkRes(TAG + "收集海洋科普知识失败:", jo)) {
                    Log.forest("神奇海洋🌊[学习海洋科普知识]#潘多拉能量+1");
                } else {
                    Log.error(TAG, jo.getString("resultDesc"));
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "collectReplicaAsset err:", t);
        }
    }

    private static void unLockReplicaPhase(String replicaCode, String replicaPhaseCode) {
        try {
            String s = AntOceanRpcCall.unLockReplicaPhase(replicaCode, replicaPhaseCode);
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "解锁海洋副本阶段失败:", jo)) {
                String name = jo.getJSONObject("currentPhaseInfo").getJSONObject("extInfo").getString("name");
                Log.forest("神奇海洋🌊迎回[" + name + "]");
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "unLockReplicaPhase err:", t);
        }
    }

    private static void queryReplicaHome() {
        try {
            String s = AntOceanRpcCall.queryReplicaHome();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋副本主页失败:", jo)) {
                if (jo.has("userReplicaAssetVO")) {
                    JSONObject userReplicaAssetVO = jo.getJSONObject("userReplicaAssetVO");
                    int canCollectAssetNum = userReplicaAssetVO.getInt("canCollectAssetNum");
                    collectReplicaAsset(canCollectAssetNum);
                }
                if (jo.has("userCurrentPhaseVO")) {
                    JSONObject userCurrentPhaseVO = jo.getJSONObject("userCurrentPhaseVO");
                    String phaseCode = userCurrentPhaseVO.getString("phaseCode");
                    String code = jo.getJSONObject("userReplicaInfoVO").getString("code");
                    if ("COMPLETED".equals(userCurrentPhaseVO.getString("phaseStatus"))) {
                        unLockReplicaPhase(code, phaseCode);
                    }
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryReplicaHome err:", t);
        }
    }

    private static void queryOceanPropList() {
        try {
            JSONObject jo = new JSONObject(AntOceanRpcCall.queryOceanPropList());
            if (ResChecker.checkRes(TAG + "查询海洋道具列表失败:", jo)) {
                checkAndCreateExtraCollect();
                AntOceanRpcCall.repairSeaArea();
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryOceanPropList err:", t);
        }
    }

    private void switchOceanChapter() {
        String s = AntOceanRpcCall.queryOceanChapterList();
        try {
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋章节列表失败:", jo)) {
                String currentChapterCode = jo.getString("currentChapterCode");
                JSONArray chapterVOs = jo.getJSONArray("userChapterDetailVOList");
                boolean isFinish = false;
                String dstChapterCode = "";
                String dstChapterName = "";
                for (int i = 0; i < chapterVOs.length(); i++) {
                    JSONObject chapterVO = chapterVOs.getJSONObject(i);
                    int repairedSeaAreaNum = chapterVO.getInt("repairedSeaAreaNum");
                    int seaAreaNum = chapterVO.getInt("seaAreaNum");
                    if (chapterVO.getString("chapterCode").equals(currentChapterCode)) {
                        isFinish = repairedSeaAreaNum >= seaAreaNum;
                    } else {
                        if (repairedSeaAreaNum >= seaAreaNum || !chapterVO.getBoolean("chapterOpen")) {
                            continue;
                        }
                        dstChapterName = chapterVO.getString("chapterName");
                        dstChapterCode = chapterVO.getString("chapterCode");
                    }
                }

                if (isFinish && !StringUtil.isEmpty(dstChapterCode)) {
                    Log.record(TAG, "当前海域已完成，等待切换...");
                    GlobalThreadPools.sleepCompat(5000);

                    // 切换动作
                    s = AntOceanRpcCall.switchOceanChapter(dstChapterCode);
                    jo = new JSONObject(s);
                    if (ResChecker.checkRes(TAG + "切换海洋章节失败:", jo)) {
                        Log.forest("神奇海洋🌊切换到[" + dstChapterName + "]系列");
                    } else {
                        Log.error(TAG, jo.getString("resultDesc"));
                    }
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "switchOceanChapter err:", t);
        }
    }

    private void querySeaAreaDetailList() {
        try {
            String s = AntOceanRpcCall.querySeaAreaDetailList();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋区域详情失败:", jo)) {

                // 1. 检查接取
                if (jo.optBoolean("awardSeaAreaCanCreateExtraCollect", false)) {
                    String availableCode = jo.optString("awardSeaAreaCode", "");
                    Log.record(TAG, "发现海域[" + availableCode + "]限时挑战，正在自动接取...");
                    String createRet = AntOceanRpcCall.createSeaAreaExtraCollect();
                    if (ResChecker.checkRes(TAG + "接取限时挑战:", new JSONObject(createRet))) {
                        Log.forest("限时挑战🌊接取成功");
                        querySeaAreaDetailList();
                        return;
                    }
                }

                int seaAreaNum = jo.getInt("seaAreaNum");
                int fixSeaAreaNum = jo.getInt("fixSeaAreaNum");
                int currentSeaAreaIndex = jo.getInt("currentSeaAreaIndex");
                if (currentSeaAreaIndex < fixSeaAreaNum && seaAreaNum > fixSeaAreaNum) {
                    queryOceanPropList();
                }

                JSONArray seaAreaVOs = jo.getJSONArray("seaAreaVOs");
                for (int i = 0; i < seaAreaVOs.length(); i++) {
                    JSONObject seaAreaVO = seaAreaVOs.getJSONObject(i);
                    // 普通鱼
                    JSONArray fishVOs = seaAreaVO.optJSONArray("fishVO");
                    if (fishVOs != null) {
                        for (int j = 0; j < fishVOs.length(); j++) {
                            JSONObject fishVO = fishVOs.getJSONObject(j);
                            if (!fishVO.getBoolean("unlock") && "COMPLETED".equals(fishVO.getString("status"))) {
                                String fishId = fishVO.getString("id");
                                combineFish(fishId, "");
                            }
                        }
                    }
                    JSONObject seaAreaExtraCollectVO = seaAreaVO.optJSONObject("seaAreaExtraCollectVO");
                    if (seaAreaExtraCollectVO != null) {
                        JSONArray extraFishVOs = seaAreaExtraCollectVO.optJSONArray("fishVO");
                        if (extraFishVOs != null) {
                            for (int j = 0; j < extraFishVOs.length(); j++) {
                                JSONObject fishVO = extraFishVOs.getJSONObject(j);
                                if (!fishVO.getBoolean("unlock") && "COMPLETED".equals(fishVO.optString("status"))) {
                                    String fishId = fishVO.getString("id");
                                    String name = fishVO.optString("name", "未知鱼类");
                                    Log.record(TAG, "发现限时挑战鱼类可合成: " + name);
                                    combineFish(fishId, "EXTRA_COLLECT");
                                }
                            }
                        }
                    }
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "querySeaAreaDetailList err:", t);
        }
    }


    private void cleanFriendOcean(JSONObject fillFlag) {
        if (!fillFlag.optBoolean("canClean")) {
            return;
        }
        try {
            String userId = fillFlag.getString("userId");
            boolean isOceanClean = cleanOceanList.getValue().contains(userId);
            if (cleanOceanType.getValue() == CleanOceanType.DONT_CLEAN) {
                isOceanClean = !isOceanClean;
            }
            if (!isOceanClean) {
                return;
            }
            String s = AntOceanRpcCall.queryFriendPage(userId);
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询好友海洋页面失败:", jo)) {
                s = AntOceanRpcCall.cleanFriendOcean(userId);
                jo = new JSONObject(s);
                Log.forest("神奇海洋🌊[帮助:" + UserMap.getMaskName(userId) + "清理海域]");
                if (ResChecker.checkRes(TAG + "清理好友海洋失败:", jo)) {
                    JSONArray cleanRewardVOS = jo.getJSONArray("cleanRewardVOS");
                    checkReward(cleanRewardVOS);
                } else {
                    Log.error(TAG, jo.getString("resultDesc"));
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryMiscInfo err:", t);
        }
    }

    private void queryUserRanking() {
        try {
            String s = AntOceanRpcCall.queryUserRanking();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋用户排行榜失败:", jo)) {
                JSONArray fillFlagVOList = jo.getJSONArray("fillFlagVOList");
                for (int i = 0; i < fillFlagVOList.length(); i++) {
                    JSONObject fillFlag = fillFlagVOList.getJSONObject(i);
                    if (cleanOcean.getValue()) {
                        cleanFriendOcean(fillFlag);
                    }
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryMiscInfo err:", t);
        }
    }


    private void receiveTaskAward() {
        try {
            Set<String> presetBad = new LinkedHashSet<>(List.of("DEMO", "DEMO1"));

            TypeReference<Set<String>> typeRef = new TypeReference<>() {
            };
            Set<String> badTaskSet = DataStore.INSTANCE.getOrCreate("badOceanTaskSet", typeRef);
            if (badTaskSet.isEmpty()) {
                badTaskSet.addAll(presetBad);
                DataStore.INSTANCE.put("badOceanTaskSet", badTaskSet);
            }
            while (true) {
                boolean done = false;
                String s = AntOceanRpcCall.queryTaskList();
                JSONObject jo = new JSONObject(s);
                if (!ResChecker.checkRes(TAG + "查询海洋任务列表失败:", jo)) {
                    Log.record(TAG, "查询任务列表失败：" + jo.getString("resultDesc"));
                }
                JSONArray jaTaskList = jo.getJSONArray("antOceanTaskVOList");
                for (int i = 0; i < jaTaskList.length(); i++) {
                    JSONObject task = jaTaskList.getJSONObject(i);
                    JSONObject bizInfo = new JSONObject(task.getString("bizInfo"));
                    String taskTitle = bizInfo.optString("taskTitle");
                    String awardCount = bizInfo.optString("awardCount", "0");
                    String sceneCode = task.getString("sceneCode");
                    String taskType = task.getString("taskType");
                    String taskStatus = task.getString("taskStatus");
                    if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                        JSONObject joAward = new JSONObject(AntOceanRpcCall.receiveTaskAward(sceneCode, taskType));
                        if (ResChecker.checkRes(TAG + "领取海洋任务奖励失败:", joAward)) {
                            Log.forest("海洋奖励🌊[" + taskTitle + "]# " + awardCount + "拼图");
                            done = true;
                        } else {
                            Log.error(TAG, "海洋奖励🌊领取失败：" + joAward);
                        }
                        GlobalThreadPools.sleepCompat(500);
                    } else if (TaskStatus.TODO.name().equals(taskStatus)) {
                        if (badTaskSet.contains(taskTitle)) {
                            Log.record(TAG, "海洋任务🌊[" + taskTitle + "]已在黑名单中，跳过处理");
                            continue;
                        }
                        if (taskTitle.contains("答题")) {
                            answerQuestion();
                        } else {
                            String bizKey = sceneCode + "_" + taskType;
                            int count = oceanTaskTryCount
                                    .computeIfAbsent(bizKey, k -> new AtomicInteger(0))
                                    .incrementAndGet();

                            JSONObject joFinishTask = new JSONObject(AntOceanRpcCall.finishTask(sceneCode, taskType));
                            String errorCode = joFinishTask.optString("code", "");
                            String desc = joFinishTask.optString("desc", "");
                            if ("400000040".equals(errorCode) || desc.contains("不支持RPC完成") ) {
                                Log.error(TAG, "海洋任务🌊[" + taskTitle + "]不支持RPC完成，已加入黑名单");
                                badTaskSet.add(taskTitle);
                                DataStore.INSTANCE.put("badOceanTaskSet", badTaskSet);
                                continue;
                            }
                            if (count > 1) {
                                badTaskSet.add(taskType);
                                DataStore.INSTANCE.put("badOceanTaskSet", badTaskSet);
                            } else {
                                if (ResChecker.checkRes(TAG, joFinishTask)) {
                                    Log.forest("海洋任务🌊完成[" + taskTitle + "]");
                                    done = true;
                                } else {
                                    Log.error(TAG, "海洋任务🌊完成失败：" + joFinishTask);
                                }
                            }

                        }
                        GlobalThreadPools.sleepCompat(500);
                    }
                }
                if (!done) break;
            }
        } catch (JSONException e) {
            Log.printStackTrace(TAG,"JSON解析错误: " ,e);
        } catch (
                Throwable t) {
            Log.printStackTrace(TAG, "receiveTaskAward err:", t);
        }
    }

    private static void answerQuestion() {
        try {
            String questionResponse = AntOceanRpcCall.getQuestion();
            JSONObject questionJson = new JSONObject(questionResponse);
            if (questionJson.getBoolean("answered")) {
                Log.record(TAG, "问题已经被回答过，跳过答题流程");
                return;
            }
            if (questionJson.getInt("resultCode") == 200) {
                String questionId = questionJson.getString("questionId");
                JSONArray options = questionJson.getJSONArray("options");
                String answer = options.getString(0);
                String submitResponse = AntOceanRpcCall.submitAnswer(answer, questionId);
                JSONObject submitJson = new JSONObject(submitResponse);
                if (submitJson.getInt("resultCode") == 200) {
                    Log.forest(TAG, "🌊海洋答题成功");
                } else {
                    Log.error(TAG, "海洋答题失败：" + submitJson);
                }
            } else {
                Log.error(TAG, "海洋获取问题失败：" + questionJson);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "海洋答题错误", t);
        }
    }

    private static void doOceanPDLTask() {
        try {
            Log.record(TAG, "执行潘多拉海域任务");
            String homeResponse = AntOceanRpcCall.PDLqueryReplicaHome();
            JSONObject homeJson = new JSONObject(homeResponse);
            if (ResChecker.checkRes(TAG + "查询潘多拉海洋副本主页失败:", homeJson)) {
                String taskListResponse = AntOceanRpcCall.PDLqueryTaskList();
                JSONObject taskListJson = new JSONObject(taskListResponse);
                JSONArray antOceanTaskVOList = taskListJson.getJSONArray("antOceanTaskVOList");
                for (int i = 0; i < antOceanTaskVOList.length(); i++) {
                    JSONObject task = antOceanTaskVOList.getJSONObject(i);
                    String taskStatus = task.getString("taskStatus");
                    if ("FINISHED".equals(taskStatus)) {
                        String bizInfoString = task.getString("bizInfo");
                        JSONObject bizInfo = new JSONObject(bizInfoString);
                        String taskTitle = bizInfo.getString("taskTitle");
                        int awardCount = bizInfo.getInt("awardCount");
                        String taskType = task.getString("taskType");
                        String receiveTaskResponse = AntOceanRpcCall.PDLreceiveTaskAward(taskType);
                        JSONObject receiveTaskJson = new JSONObject(receiveTaskResponse);
                        int code = receiveTaskJson.getInt("code");
                        if (code == 100000000) {
                            Log.forest("海洋奖励🌊[领取:" + taskTitle + "]获得潘多拉能量x" + awardCount);
                        } else {
                            if (receiveTaskJson.has("message")) {
                                Log.record(TAG, "领取任务奖励失败: " + receiveTaskJson.getString("message"));
                            } else {
                                Log.record(TAG, "领取任务奖励失败，未返回错误信息");
                            }
                        }
                    }
                }
            } else {
                Log.record(TAG, "PDLqueryReplicaHome调用失败: " + homeJson.optString("message"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "doOceanPDLTask err:", t);
        }
    }

    private void protectOcean() {
        try {
            String s = AntOceanRpcCall.queryCultivationList();
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋培育列表失败:", jo)) {
                JSONArray ja = jo.getJSONArray("cultivationItemVOList");
                for (int i = 0; i < ja.length(); i++) {
                    jo = ja.getJSONObject(i);
                    String templateSubType = jo.getString("templateSubType");
                    String applyAction = jo.getString("applyAction");
                    String cultivationName = jo.getString("cultivationName");
                    String templateCode = jo.getString("templateCode");
                    JSONObject projectConfig = jo.getJSONObject("projectConfigVO");
                    String projectCode = projectConfig.getString("code");
                    Map<String, Integer> map = protectOceanList.getValue();
                    for (Map.Entry<String, Integer> entry : map.entrySet()) {
                        if (Objects.equals(entry.getKey(), templateCode)) {
                            Integer count = entry.getValue();
                            if (count != null && count > 0) {
                                oceanExchangeTree(templateCode, projectCode, cultivationName, count);
                            }
                            break;
                        }
                    }
                }
            } else {
                Log.error(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "protectBeach err:", t);
        }
    }

    private static void oceanExchangeTree(String cultivationCode, String projectCode, String itemName, int count) {
        try {
            String s;
            JSONObject jo;
            int appliedTimes = queryCultivationDetail(cultivationCode, projectCode, count);
            if (appliedTimes < 0)
                return;
            for (int applyCount = 1; applyCount <= count; applyCount++) {
                s = AntOceanRpcCall.oceanExchangeTree(cultivationCode, projectCode);
                jo = new JSONObject(s);
                if (ResChecker.checkRes(TAG + "海洋兑换树木失败:", jo)) {
                    JSONArray awardInfos = jo.getJSONArray("rewardItemVOs");
                    StringBuilder award = new StringBuilder();
                    for (int i = 0; i < awardInfos.length(); i++) {
                        jo = awardInfos.getJSONObject(i);
                        award.append(jo.getString("name")).append("*").append(jo.getInt("num"));
                    }
                    String str = "保护海洋生态🏖️[" + itemName + "]#第" + appliedTimes + "次" + "-获得奖励" + award;
                    Log.forest(str);
                    GlobalThreadPools.sleepCompat(300);
                } else {
                    Log.error("保护海洋生态🏖️[" + itemName + "]#发生未知错误，停止申请");
                    break;
                }
                GlobalThreadPools.sleepCompat(300);
                appliedTimes = queryCultivationDetail(cultivationCode, projectCode, count);
                if (appliedTimes < 0) {
                    break;
                } else {
                    GlobalThreadPools.sleepCompat(300);
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "海洋保护错误:", t);
        }
    }

    private static int queryCultivationDetail(String cultivationCode, String projectCode, int count) {
        int appliedTimes = -1;
        try {
            String s = AntOceanRpcCall.queryCultivationDetail(cultivationCode, projectCode);
            JSONObject jo = new JSONObject(s);
            if (ResChecker.checkRes(TAG + "查询海洋培育详情失败:", jo)) {
                JSONObject userInfo = jo.getJSONObject("userInfoVO");
                int currentEnergy = userInfo.getInt("currentEnergy");
                jo = jo.getJSONObject("cultivationDetailVO");
                String applyAction = jo.getString("applyAction");
                int certNum = jo.getInt("certNum");
                if ("AVAILABLE".equals(applyAction)) {
                    if (currentEnergy >= jo.getInt("energy")) {
                        if (certNum < count) {
                            appliedTimes = certNum + 1;
                        }
                    } else {
                        Log.forest("保护海洋🏖️[" + jo.getString("cultivationName") + "]#能量不足停止申请");
                    }
                } else {
                    Log.forest("保护海洋🏖️[" + jo.getString("cultivationName") + "]#似乎没有了");
                }
            } else {
                Log.error(jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "queryCultivationDetail err:", t);
        }
        return appliedTimes;
    }

    private static void exchangeProp() {
        try {
            boolean shouldContinue = true;
            while (shouldContinue) {
                String propListJson = AntOceanRpcCall.exchangePropList();
                JSONObject propListObj = new JSONObject(propListJson);
                if (ResChecker.checkRes(TAG + "查询海洋道具兑换列表失败:", propListObj)) {
                    int duplicatePieceNum = propListObj.getInt("duplicatePieceNum");
                    if (duplicatePieceNum < 10) {
                        return;
                    }
                    String exchangeResultJson = AntOceanRpcCall.exchangeProp();
                    JSONObject exchangeResultObj = new JSONObject(exchangeResultJson);
                    String exchangedPieceNum = exchangeResultObj.getString("duplicatePieceNum");
                    String exchangeNum = exchangeResultObj.getString("exchangeNum");
                    if (ResChecker.checkRes(TAG + "海洋道具兑换失败:", exchangeResultObj)) {
                        Log.forest("神奇海洋🏖️[万能拼图]制作" + exchangeNum + "张,剩余" + exchangedPieceNum + "张碎片");
                        GlobalThreadPools.sleepCompat(1000);
                    }
                } else {
                    shouldContinue = false;
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "exchangeProp error:", t);
        }
    }

    private static void usePropByType() {
        try {
            String propListJson = AntOceanRpcCall.usePropByTypeList();
            JSONObject propListObj = new JSONObject(propListJson);
            if (ResChecker.checkRes(TAG + "查询海洋道具使用类型列表失败:", propListObj)) {
                JSONArray oceanPropVOByTypeList = propListObj.getJSONArray("oceanPropVOByTypeList");
                for (int i = 0; i < oceanPropVOByTypeList.length(); i++) {
                    JSONObject propInfo = oceanPropVOByTypeList.getJSONObject(i);
                    int holdsNum = propInfo.getInt("holdsNum");
                    int pageNum = 0;
                    th:
                    while (holdsNum > 0) {
                        pageNum++;
                        String fishListJson = AntOceanRpcCall.queryFishList(pageNum);
                        JSONObject fishListObj = new JSONObject(fishListJson);
                        if (!ResChecker.checkRes(TAG + "查询海洋鱼类列表失败:", fishListObj)) {
                            break;
                        }
                        JSONArray fishVOS = fishListObj.optJSONArray("fishVOS");
                        if (fishVOS == null) {
                            break;
                        }
                        for (int j = 0; j < fishVOS.length(); j++) {
                            JSONObject fish = fishVOS.getJSONObject(j);
                            JSONArray pieces = fish.optJSONArray("pieces");
                            if (pieces == null) {
                                continue;
                            }
                            int order = fish.getInt("order");
                            String name = fish.getString("name");
                            Set<Integer> idSet = new HashSet<>();
                            for (int k = 0; k < pieces.length(); k++) {
                                JSONObject piece = pieces.getJSONObject(k);
                                if (piece.optInt("num") == 0) {
                                    idSet.add(Integer.parseInt(piece.getString("id")));
                                    holdsNum--;
                                    if (holdsNum <= 0) {
                                        break;
                                    }
                                }
                            }
                            if (!idSet.isEmpty()) {
                                String usePropResult = AntOceanRpcCall.usePropByType(order, idSet);
                                JSONObject usePropResultObj = new JSONObject(usePropResult);
                                if (ResChecker.checkRes(TAG + "使用海洋万能拼图失败:", usePropResultObj)) {
                                    int userCount = idSet.size();
                                    Log.forest("神奇海洋🏖️[万能拼图]使用" + userCount + "张，获得[" + name + "]剩余" + holdsNum + "张");
                                    GlobalThreadPools.sleepCompat(1000);
                                    if (holdsNum <= 0) {
                                        break th;
                                    }
                                }
                            }
                        }
                        if (!fishListObj.optBoolean("hasMore")) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG,  "usePropByType error:",t);
        }
    }

    static final String AI_FISH_MAIN_SCENE = "ANTAIFISH";
    static final String AI_FISH_RESCUE_SCENE = "ANTAIFISH_RESCUE_AND_RESTORE";

    interface AiFishGateway {
        default boolean active() { return true; }
        String queryStatus();

        String queryHome();

        String listTasks(String sceneCode);

        String finishTask(String sceneCode, String taskType);

        String receiveTaskAward(String sceneCode, String taskType);

        boolean hasCompletedToday(String taskType);

        void markCompletedToday(String taskType);

        String rescueFish();

        String touchFish();

        void waitMillis(long millis);
    }

    static final class AiFishHomeSnapshot {
        private final boolean recognized;
        private final String fishStatus;
        private final Integer remainTouchChance;
        private final Integer touchTotal;

        AiFishHomeSnapshot(
                boolean recognized,
                String fishStatus,
                Integer remainTouchChance,
                Integer touchTotal
        ) {
            this.recognized = recognized;
            this.fishStatus = fishStatus;
            this.remainTouchChance = remainTouchChance;
            this.touchTotal = touchTotal;
        }

        public boolean isRecognized() {
            return recognized;
        }

        public String getFishStatus() {
            return fishStatus;
        }

        public Integer getRemainTouchChance() {
            return remainTouchChance;
        }

        public Integer getTouchTotal() {
            return touchTotal;
        }
    }

    static final class AiFishTask {
        private final String sceneCode;
        private final String taskType;
        private final String title;
        private final String status;
        private final int waitSeconds;
        private final String playType;

        AiFishTask(
                String sceneCode,
                String taskType,
                String title,
                String status,
                int waitSeconds,
                String playType
        ) {
            this.sceneCode = sceneCode;
            this.taskType = taskType;
            this.title = title;
            this.status = status;
            this.waitSeconds = waitSeconds;
            this.playType = playType;
        }

        public String getSceneCode() {
            return sceneCode;
        }

        public String getTaskType() {
            return taskType;
        }

        public String getTitle() {
            return title;
        }

        public String getStatus() {
            return status;
        }

        public int getWaitSeconds() {
            return waitSeconds;
        }

        public String getPlayType() {
            return playType;
        }
    }

    static final class AiFishTaskSnapshot {
        private final boolean recognized;
        private final List<AiFishTask> tasks;

        AiFishTaskSnapshot(boolean recognized, List<AiFishTask> tasks) {
            this.recognized = recognized;
            this.tasks = tasks;
        }

        public boolean isRecognized() {
            return recognized;
        }

        public List<AiFishTask> getTasks() {
            return tasks;
        }
    }

    static final class AiFishRunResult {
        private final boolean available;
        private final boolean rescued;
        private final int completedTaskCount;
        private final int receivedRewardCount;
        private final int touchCount;
        private final List<String> events;

        AiFishRunResult(
                boolean available,
                boolean rescued,
                int completedTaskCount,
                int receivedRewardCount,
                int touchCount,
                List<String> events
        ) {
            this.available = available;
            this.rescued = rescued;
            this.completedTaskCount = completedTaskCount;
            this.receivedRewardCount = receivedRewardCount;
            this.touchCount = touchCount;
            this.events = events;
        }

        public boolean isAvailable() {
            return available;
        }

        public boolean isRescued() {
            return rescued;
        }

        public int getCompletedTaskCount() {
            return completedTaskCount;
        }

        public int getReceivedRewardCount() {
            return receivedRewardCount;
        }

        public int getTouchCount() {
            return touchCount;
        }

        public List<String> getEvents() {
            return events;
        }
    }

    static AiFishHomeSnapshot parseAiFishHome(String response) {
        JSONObject root = aiFishResponseRoot(response);
        if (root == null) {
            return unknownAiFishHome();
        }
        JSONObject interact = root.optJSONObject("myFish");
        interact = interact == null ? null : interact.optJSONObject("interactVO");
        if (interact == null
                || !interact.has("fishInteractStatus")
                || !interact.has("remainTouchChance")
                || !interact.has("touchTotal")) {
            return unknownAiFishHome();
        }
        return new AiFishHomeSnapshot(
                true,
                interact.optString("fishInteractStatus"),
                interact.optInt("remainTouchChance"),
                interact.optInt("touchTotal")
        );
    }

    static AiFishTaskSnapshot parseAiFishTasks(String response) {
        JSONObject root = aiFishResponseRoot(response);
        JSONArray taskArray = root == null ? null : root.optJSONArray("taskInfoList");
        if (taskArray == null) {
            return new AiFishTaskSnapshot(false, List.of());
        }
        List<AiFishTask> tasks = new ArrayList<>();
        for (int index = 0; index < taskArray.length(); index++) {
            AiFishTask task = parseAiFishTask(taskArray.optJSONObject(index));
            if (task != null) {
                tasks.add(task);
            }
        }
        return new AiFishTaskSnapshot(true, tasks);
    }

    static boolean isAiFishActionAccepted(String response) {
        JSONObject root = aiFishResponseRoot(response);
        return root != null && (root.optBoolean("success", false)
                || "SUCCESS".equalsIgnoreCase(root.optString("resultCode"))
                || "100000000".equals(root.optString("code")));
    }

    static AiFishTask selectAiFishRescueTask(AiFishTaskSnapshot snapshot) {
        if (!snapshot.isRecognized()) {
            return null;
        }
        AiFishTask selected = null;
        for (AiFishTask task : snapshot.getTasks()) {
            boolean eligible = AI_FISH_RESCUE_SCENE.equals(task.getSceneCode())
                    && "TODO".equalsIgnoreCase(task.getStatus())
                    && "VISIT_FLOAT_BALL".equalsIgnoreCase(task.getPlayType())
                    && task.getWaitSeconds() > 0;
            if (eligible && (selected == null
                    || task.getTaskType().compareTo(selected.getTaskType()) < 0)) {
                selected = task;
            }
        }
        return selected;
    }

    private static AiFishTask parseAiFishTask(JSONObject task) {
        JSONObject baseInfo = task == null ? null : task.optJSONObject("taskBaseInfo");
        if (baseInfo == null) {
            return null;
        }
        String taskType = baseInfo.optString("taskType").trim();
        String sceneCode = baseInfo.optString("sceneCode").trim();
        String status = baseInfo.optString("taskStatus").trim();
        if (taskType.isEmpty() || sceneCode.isEmpty() || status.isEmpty()) {
            return null;
        }
        JSONObject bizInfo = aiFishObjectValue(baseInfo, "bizInfo");
        JSONObject playParam = aiFishObjectValue(baseInfo, "prodPlayParam");
        int waitSeconds = playParam == null ? 0 : playParam.optInt("timeCount", 0);
        waitSeconds = Math.max(0, Math.min(60, waitSeconds));
        return new AiFishTask(
                sceneCode,
                taskType,
                bizInfo == null ? "" : bizInfo.optString("taskTitle"),
                status,
                waitSeconds,
                baseInfo.optString("taskProdPlayType")
        );
    }

    private static JSONObject aiFishObjectValue(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (value instanceof JSONObject jsonObject) {
            return jsonObject;
        }
        if (value instanceof String text) {
            try {
                return new JSONObject(text);
            } catch (JSONException ignored) {
                return null;
            }
        }
        return null;
    }

    private static JSONObject aiFishResponseRoot(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject data = root.optJSONObject("resData");
            return data == null ? root : data;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AiFishHomeSnapshot unknownAiFishHome() {
        return new AiFishHomeSnapshot(false, null, null, null);
    }

    static final class AiFishRunner {
        private static final int MAX_TASK_PASSES = 50;
        private static final int MAX_TOUCH_COUNT = 20;
        private static final Set<String> DAILY_ONCE_MAIN_TASK_TYPES =
                Set.of("AIFISH_ZHUANHUA_BWXRK");

        private final AiFishGateway gateway;
        private final List<String> events = new ArrayList<>();
        private int completedTaskCount;
        private int receivedRewardCount;

        AiFishRunner(AiFishGateway gateway) {
            this.gateway = gateway;
        }

        AiFishRunResult run() {
            if (!gateway.active()) return result(false, false, 0);
            if (!isAiFishActionAccepted(gateway.queryStatus())) {
                events.add("AI摸鱼状态接口不可用");
                return result(false, false, 0);
            }
            AiFishHomeSnapshot home = parseAiFishHome(gateway.queryHome());
            if (!home.isRecognized()) {
                events.add("AI摸鱼主页结构未知");
                return result(true, false, 0);
            }

            boolean rescued = false;
            if ("CAPTURED".equalsIgnoreCase(home.getFishStatus())) {
                rescued = rescueCapturedFish();
                if (!rescued) {
                    return result(true, false, 0);
                }
            }

            processMainTasks();
            return result(true, rescued, touchAvailableFish());
        }

        private boolean rescueCapturedFish() {
            if (!gateway.active()) return false;
            AiFishTask task = selectAiFishRescueTask(
                    parseAiFishTasks(gateway.listTasks(AI_FISH_RESCUE_SCENE))
            );
            if (task == null) {
                events.add("AI摸鱼未找到可用找回任务");
                return false;
            }
            events.add("AI摸鱼找回任务等待" + task.getWaitSeconds() + "秒");
            gateway.waitMillis((task.getWaitSeconds() + 1L) * 1000L);
            if (!gateway.active()) return false;
            if (!isAiFishActionAccepted(gateway.rescueFish())) {
                events.add("AI摸鱼找回接口未受理");
                return false;
            }
            AiFishHomeSnapshot confirmed = parseAiFishHome(gateway.queryHome());
            boolean rescued = confirmed.isRecognized()
                    && !"CAPTURED".equalsIgnoreCase(confirmed.getFishStatus());
            events.add(rescued ? "AI摸鱼被抓的鱼已找回" : "AI摸鱼找回状态未确认");
            return rescued;
        }

        private void processMainTasks() {
            Set<String> attemptedTasks = new LinkedHashSet<>();
            Set<String> attemptedRewards = new LinkedHashSet<>();
            for (int pass = 0; pass < MAX_TASK_PASSES; pass++) {
                if (!gateway.active()) return;
                AiFishTaskSnapshot snapshot = parseAiFishTasks(
                        gateway.listTasks(AI_FISH_MAIN_SCENE)
                );
                if (!snapshot.isRecognized()) {
                    events.add("AI摸鱼主任务结构未知");
                    return;
                }
                boolean attemptedInPass = false;
                for (AiFishTask task : snapshot.getTasks()) {
                    if (!gateway.active()) return;
                    try {
                        if ("FINISHED".equalsIgnoreCase(task.getStatus())
                                && attemptedRewards.add(task.getTaskType())) {
                            attemptedInPass = true;
                            markCompletedTodayIfNeeded(task.getTaskType());
                            claimAndConfirm(task);
                        } else if ("TODO".equalsIgnoreCase(task.getStatus())
                                && attemptedTasks.add(task.getTaskType())) {
                            if (shouldSkipToday(task)) {
                                events.add("AI摸鱼任务今日已完成，跳过[" + task.getTitle() + "]");
                            } else {
                                attemptedInPass = true;
                                finishAndConfirm(task, attemptedRewards);
                            }
                        }
                    } catch (Throwable t) {
                        events.add("AI摸鱼任务异常，已跳过[" + task.getTitle() + "]");
                    }
                }
                if (!attemptedInPass) {
                    return;
                }
            }
            events.add("AI摸鱼主任务达到轮询上限");
        }

        private void finishAndConfirm(AiFishTask task, Set<String> attemptedRewards) {
            events.add("AI摸鱼任务等待" + task.getWaitSeconds() + "秒[" + task.getTitle() + "]");
            gateway.waitMillis(task.getWaitSeconds() * 1000L);
            if (!gateway.active()) return;
            if (!isAiFishActionAccepted(
                    gateway.finishTask(task.getSceneCode(), task.getTaskType())
            )) {
                events.add("AI摸鱼任务完成未受理[" + task.getTitle() + "]");
                return;
            }
            AiFishTask after = queryMainTask(task.getTaskType());
            if (after != null && "FINISHED".equalsIgnoreCase(after.getStatus())) {
                markCompletedTodayIfNeeded(task.getTaskType());
                completedTaskCount++;
                events.add("AI摸鱼任务完成已确认[" + task.getTitle() + "]");
                if (attemptedRewards.add(task.getTaskType())) {
                    claimAndConfirm(after);
                }
            } else if (after != null && "RECEIVED".equalsIgnoreCase(after.getStatus())) {
                completedTaskCount++;
                markCompletedTodayIfNeeded(task.getTaskType());
                events.add("AI摸鱼任务已直接领取[" + task.getTitle() + "]");
            } else {
                events.add("AI摸鱼任务状态未推进[" + task.getTitle() + "]");
            }
        }

        private void claimAndConfirm(AiFishTask task) {
            if (!gateway.active()) return;
            if (!isAiFishActionAccepted(
                    gateway.receiveTaskAward(task.getSceneCode(), task.getTaskType())
            )) {
                events.add("AI摸鱼奖励领取未受理[" + task.getTitle() + "]");
                return;
            }
            AiFishTask after = queryMainTask(task.getTaskType());
            if (after != null && "RECEIVED".equalsIgnoreCase(after.getStatus())) {
                receivedRewardCount++;
                events.add("AI摸鱼奖励领取已确认[" + task.getTitle() + "]");
            } else {
                events.add("AI摸鱼奖励状态未推进[" + task.getTitle() + "]");
            }
        }

        private AiFishTask queryMainTask(String taskType) {
            if (!gateway.active()) return null;
            AiFishTaskSnapshot snapshot = parseAiFishTasks(
                    gateway.listTasks(AI_FISH_MAIN_SCENE)
            );
            if (!snapshot.isRecognized()) {
                return null;
            }
            for (AiFishTask task : snapshot.getTasks()) {
                if (taskType.equals(task.getTaskType())) {
                    return task;
                }
            }
            return null;
        }

        private boolean shouldSkipToday(AiFishTask task) {
            return DAILY_ONCE_MAIN_TASK_TYPES.contains(task.getTaskType())
                    && gateway.hasCompletedToday(task.getTaskType());
        }

        private void markCompletedTodayIfNeeded(String taskType) {
            if (gateway.active() && DAILY_ONCE_MAIN_TASK_TYPES.contains(taskType)) {
                gateway.markCompletedToday(taskType);
            }
        }

        private int touchAvailableFish() {
            if (!gateway.active()) return 0;
            AiFishHomeSnapshot before = parseAiFishHome(gateway.queryHome());
            if (!before.isRecognized()) {
                events.add("AI摸鱼主页复查结构未知");
                return 0;
            }
            int touchCount = 0;
            for (int count = 0; count < MAX_TOUCH_COUNT; count++) {
                if (!gateway.active()) return touchCount;
                if (before.getRemainTouchChance() == null
                        || before.getRemainTouchChance() <= 0) {
                    return touchCount;
                }
                String response = gateway.touchFish();
                if (!isAiFishActionAccepted(response)) {
                    events.add("AI摸鱼动作未受理");
                    return touchCount;
                }
                AiFishHomeSnapshot after = parseAiFishHome(response);
                if (!after.isRecognized()) {
                    events.add("AI摸鱼响应结构未知");
                    return touchCount;
                }
                boolean progressed = after.getTouchTotal() > before.getTouchTotal()
                        || after.getRemainTouchChance() < before.getRemainTouchChance();
                if (!progressed) {
                    events.add("AI摸鱼状态无进展");
                    return touchCount;
                }
                touchCount++;
                events.add("AI摸鱼成功，累计" + after.getTouchTotal() + "次");
                before = after;
            }
            events.add("AI摸鱼达到单轮上限");
            return touchCount;
        }

        private AiFishRunResult result(boolean available, boolean rescued, int touchCount) {
            return new AiFishRunResult(
                    available,
                    rescued,
                    completedTaskCount,
                    receivedRewardCount,
                    touchCount,
                    List.copyOf(events)
            );
        }
    }

    @SuppressWarnings("unused")
    public interface CleanOceanType {
        int CLEAN = 0;
        int DONT_CLEAN = 1;
        String[] nickNames = {"选中清理", "选中不清理"};
    }
}
