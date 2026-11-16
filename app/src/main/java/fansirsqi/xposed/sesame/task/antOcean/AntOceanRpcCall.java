package fansirsqi.xposed.sesame.task.antOcean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fansirsqi.xposed.sesame.hook.RequestManager;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.RandomUtil;

import java.util.Set;

/**
 * @author Constanline
 * @since 2023/08/01
 */
public class AntOceanRpcCall {
    private static final String VERSION = "20241203";
    static final String AI_FISH_STATUS_METHOD = "alipay.antaifish.h5.status";
    static final String AI_FISH_HOMEPAGE_METHOD = "alipay.antaifish.h5.homepage";
    static final String AI_FISH_LIST_TASKS_METHOD = "com.alipay.antieptask.listTaskopengreen";
    static final String AI_FISH_FINISH_TASK_METHOD = "com.alipay.antiep.finishTask";
    static final String AI_FISH_RECEIVE_AWARD_METHOD = "com.alipay.antieptask.receiveTaskAwardopengreen";
    static final String AI_FISH_RESCUE_METHOD = "alipay.antaifish.h5.rescueFish";
    static final String AI_FISH_TOUCH_METHOD = "alipay.antaifish.h5.touchfish";

    private static String getUniqueId() {
        return String.valueOf(System.currentTimeMillis()) + RandomUtil.nextLong();
    }

    public static String aiFishStatus() {
        return RequestManager.requestString(
                AI_FISH_STATUS_METHOD,
                buildAiFishStatusArgs(getUniqueId())
        );
    }

    public static String aiFishHomepage() {
        return RequestManager.requestString(
                AI_FISH_HOMEPAGE_METHOD,
                buildAiFishOceanActionArgs(getUniqueId())
        );
    }

    public static String aiFishListTasks(String sceneCode) {
        return RequestManager.requestString(
                AI_FISH_LIST_TASKS_METHOD,
                buildAiFishListTasksArgs(sceneCode, getUniqueId())
        );
    }

    public static String aiFishFinishTask(String sceneCode, String taskType) {
        return RequestManager.requestString(
                AI_FISH_FINISH_TASK_METHOD,
                buildAiFishFinishTaskArgs(
                        sceneCode,
                        taskType,
                        taskType + "_" + RandomUtil.nextDouble(),
                        getUniqueId()
                )
        );
    }

    public static String aiFishReceiveTaskAward(String sceneCode, String taskType) {
        return RequestManager.requestString(
                AI_FISH_RECEIVE_AWARD_METHOD,
                buildAiFishReceiveTaskAwardArgs(sceneCode, taskType, getUniqueId())
        );
    }

    public static String aiFishRescue() {
        return RequestManager.requestString(
                AI_FISH_RESCUE_METHOD,
                buildAiFishOceanActionArgs(getUniqueId())
        );
    }

    public static String aiFishTouch() {
        return RequestManager.requestString(
                AI_FISH_TOUCH_METHOD,
                buildAiFishOceanActionArgs(getUniqueId())
        );
    }

    public static String aiFishDraw() {
        String image = "JNVwTJAPzqMAAAAAQOAAAAgA9KBtAQJr";
        return RequestManager.requestString("alipay.antaifish.h5.drawFish", arguments(jsonObject(
                "imgId", image, "imgUrl", "https://mdn.alipayobjects.com/afts/img/" + image + "/original?bz=ai_fish",
                "source", "ANT_OCEAN", "uniqueId", getUniqueId())), 1, 0);
    }

    public static String aiFishAppraiseList() {
        return RequestManager.requestString("alipay.antaifish.h5.queryNeedAppraiseFishList", buildAiFishOceanActionArgs(getUniqueId()));
    }

    public static String aiFishLike(String imageId, String seasonId, String userId) {
        String unique = System.currentTimeMillis() + "_" + String.format(java.util.Locale.ROOT, "%08x", RandomUtil.nextInt(0, Integer.MAX_VALUE));
        return RequestManager.requestString("alipay.antaifish.h5.fishAppraise", arguments(jsonObject(
                "appraiseType", "LIKE", "imageId", imageId, "seasonId", seasonId,
                "source", "ANT_OCEAN", "targetUserId", userId, "uniqueId", unique)), 1, 0);
    }

    static String buildAiFishStatusArgs(String uniqueId) {
        return arguments(jsonObject(
                "source", "nengliangtixing",
                "uniqueId", uniqueId
        ));
    }

    static String buildAiFishOceanActionArgs(String uniqueId) {
        return arguments(jsonObject(
                "source", "ANT_OCEAN",
                "uniqueId", uniqueId
        ));
    }

    static String buildAiFishListTasksArgs(String sceneCode, String uniqueId) {
        return arguments(jsonObject(
                "extend", jsonObject("appMode", "normal"),
                "requestType", "RPC",
                "sceneCode", sceneCode,
                "source", "ANTAIFISH",
                "uniqueId", uniqueId
        ));
    }

    static String buildAiFishFinishTaskArgs(
            String sceneCode,
            String taskType,
            String outBizNo,
            String uniqueId
    ) {
        return arguments(jsonObject(
                "outBizNo", outBizNo,
                "requestType", "RPC",
                "sceneCode", sceneCode,
                "source", "ANTAIFISH",
                "taskType", taskType,
                "uniqueId", uniqueId
        ));
    }

    static String buildAiFishReceiveTaskAwardArgs(
            String sceneCode,
            String taskType,
            String uniqueId
    ) {
        return arguments(jsonObject(
                "ignoreLimit", false,
                "requestType", "RPC",
                "sceneCode", sceneCode,
                "source", "ANTAIFISH",
                "taskType", taskType,
                "uniqueId", uniqueId
        ));
    }

    private static JSONObject jsonObject(Object... keyValues) {
        JSONObject value = new JSONObject();
        try {
            for (int index = 0; index < keyValues.length; index += 2) {
                value.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
            }
            return value;
        } catch (JSONException e) {
            throw new IllegalStateException("构造 AI 摸鱼请求参数失败", e);
        }
    }

    private static String arguments(JSONObject request) {
        return new JSONArray().put(request).toString();
    }

    public static String queryOceanStatus() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryOceanStatus",
                "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]");
    }

    public static String queryHomePage() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryHomePage",
                "[{\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String cleanOcean(String userId) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.cleanOcean",
                "[{\"cleanedUserId\":\"" + userId + "\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String ipOpenSurprise() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.ipOpenSurprise",
                "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String collectReplicaAsset() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.collectReplicaAsset",
                "[{\"replicaCode\":\"avatar\",\"source\":\"senlinzuoshangjiao\",\"uniqueId\":\"" + getUniqueId() +
                        "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String receiveTaskAward(String sceneCode, String taskType) {
        return RequestManager.requestString("com.alipay.antiep.receiveTaskAward",
                "[{\"ignoreLimit\":false,\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"ANT_FOREST\",\"taskType\":\"" +
                        taskType + "\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String finishTask(String sceneCode, String taskType) {
        String outBizNo = taskType + "_" + RandomUtil.nextDouble();
        return RequestManager.requestString("com.alipay.antiep.finishTask",
                "[{\"outBizNo\":\"" + outBizNo + "\",\"requestType\":\"RPC\",\"sceneCode\":\"" +
                        sceneCode + "\",\"source\":\"ANTFOCEAN\",\"taskType\":\"" + taskType + "\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String unLockReplicaPhase(String replicaCode, String replicaPhaseCode) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.unLockReplicaPhase",
                "[{\"replicaCode\":\"" + replicaCode + "\",\"replicaPhaseCode\":\"" + replicaPhaseCode +
                        "\",\"source\":\"senlinzuoshangjiao\",\"uniqueId\":\"" + getUniqueId() + "\",\"version\":\"20220707\"}]");
    }

    public static String queryReplicaHome() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryReplicaHome",
                "[{\"replicaCode\":\"avatar\",\"source\":\"senlinzuoshangjiao\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String repairSeaArea() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.repairSeaArea",
                "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String queryOceanPropList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryOceanPropList",
                "[{\"propTypeList\":\"UNIVERSAL_PIECE\",\"skipPropId\":false,\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" +
                        getUniqueId() + "\"}]");
    }

    public static String querySeaAreaDetailList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.querySeaAreaDetailList",
                "[{\"seaAreaCode\":\"\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"targetUserId\":\"\",\"uniqueId\":\"" +
                        getUniqueId() + "\"}]");
    }

    public static String queryOceanChapterList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryOceanChapterList",
                "[{\"source\":\"chInfo_ch_url-https://2021003115672468.h5app.alipay.com/www/atlasOcean.html\",\"uniqueId\":\""
                        + getUniqueId() + "\"}]");
    }

    public static String switchOceanChapter(String chapterCode) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.switchOceanChapter",
                "[{\"chapterCode\":\"" + chapterCode
                        + "\",\"source\":\"chInfo_ch_url-https://2021003115672468.h5app.alipay.com/www/atlasOcean.html\",\"uniqueId\":\""
                        + getUniqueId() + "\"}]");
    }

    public static String queryMiscInfo() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryMiscInfo",
                "[{\"queryBizTypes\":[\"HOME_TIPS_REFRESH\"],\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" +
                        getUniqueId() + "\"}]");
    }

    public static String combineFish(String fishId) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.combineFish", "[{\"fishId\":\"" + fishId +
                "\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String collectEnergy(String bubbleId, String userId) {
        return RequestManager.requestString("alipay.antmember.forest.h5.collectEnergy",
                "[{\"bubbleIds\":[" + bubbleId + "],\"channel\":\"ocean\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" +
                        getUniqueId() + "\",\"userId\":\"" + userId + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String cleanFriendOcean(String userId) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.cleanFriendOcean",
                "[{\"cleanedUserId\":\"" + userId + "\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String queryFriendPage(String userId) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryFriendPage",
                "[{\"friendUserId\":\"" + userId + "\",\"interactFlags\":\"T\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" +
                        getUniqueId() + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String queryUserRanking() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryUserRanking",
                "[{\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    /* 保护海洋净滩行动 */
    public static String queryCultivationList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryCultivationList",
                "[{\"source\":\"ANT_FOREST\",\"version\":\"20231031\"}]");
    }

    public static String queryCultivationDetail(String cultivationCode, String projectCode) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryCultivationDetail",
                "[{\"cultivationCode\":\"" + cultivationCode + "\",\"projectCode\":\"" + projectCode
                        + "\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String oceanExchangeTree(String cultivationCode, String projectCode) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.exchangeTree",
                "[{\"cultivationCode\":\"" + cultivationCode + "\",\"projectCode\":\"" + projectCode
                        + "\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    // 答题
    public static String getQuestion() {
        return RequestManager.requestString("com.alipay.reading.game.dada.openDailyAnswer.getQuestion",
                "[{\"activityId\":\"363\",\"dadaVersion\":\"1.3.0\",\"version\":1}]");
    }

    public static String record() {
        return RequestManager.requestString("com.alipay.reading.game.dada.mdap.record",
                "[{\"behavior\":\"visit\",\"dadaVersion\":\"1.3.0\",\"version\":\"1\"}]");
    }

    public static String submitAnswer(String answer, String questionId) {
        return RequestManager.requestString("com.alipay.reading.game.dada.openDailyAnswer.submitAnswer",
                "[{\"activityId\":\"363\",\"answer\":\"" + answer + "\",\"dadaVersion\":\"1.3.0\",\"outBizId\":\"ANTOCEAN_DATI_PINTU_722_new\",\"questionId\":\"" + questionId + "\",\"version\":\"1\"}]");
    }

    // 潘多拉任务
    public static String PDLqueryReplicaHome() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryReplicaHome",
                "[{\"replicaCode\":\"avatar\",\"source\":\"seaAreaList\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String queryTaskList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryTaskList",
                "[{\"extend\":{}," +
                        "\"fromAct\":\"dynamic_task\",\"sceneCode\":\"ANTOCEAN_TASK\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String PDLqueryTaskList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryTaskList",
                "[{\"fromAct\":\"dynamic_task\",\"sceneCode\":\"ANTOCEAN_AVATAR_TASK\",\"source\":\"seaAreaList\",\"uniqueId\":\"" + getUniqueId() + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String PDLreceiveTaskAward(String taskType) {
        return RequestManager.requestString("com.alipay.antiep.receiveTaskAward",
                "[{\"ignoreLimit\":\"false\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTOCEAN_AVATAR_TASK\",\"source\":\"ANTFOCEAN\",\"taskType\":\"" + taskType + "\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    // 制作万能拼图
    public static String exchangePropList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryOceanPropList",
                "[{\"skipPropId\":false,\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String exchangeProp() {
        long timestamp = System.currentTimeMillis();
        return RequestManager.requestString("alipay.antocean.ocean.h5.exchangeProp",
                "[{\"bizNo\":\"" + timestamp + "\",\"exchangeNum\":\"1\",\"propCode\":\"UNIVERSAL_PIECE\",\"propType\":\"UNIVERSAL_PIECE\",\"source\":\"ANT_FOREST\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    // 使用万能拼图
    public static String usePropByTypeList() {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryOceanPropList",
                "[{\"propTypeList\":\"UNIVERSAL_PIECE\",\"skipPropId\":false,\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String queryFishList(int pageNum) {
        return RequestManager.requestString("alipay.antocean.ocean.h5.queryFishList",
                "[{\"combineStatus\":\"UNOBTAINED\",\"needSummary\":\"Y\",\"pageNum\":" + pageNum + ",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"targetUserId\":\"\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
    }

    public static String usePropByType(int assets, Set<Integer> attachAssetsSet) {
        try {
            if (!attachAssetsSet.isEmpty()) {
                JSONArray jsonArray = new JSONArray();
                for (Integer attachAssets : attachAssetsSet) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("assets", assets);
                    jsonObject.put("assetsNum", 1);
                    jsonObject.put("attachAssets", attachAssets);
                    jsonObject.put("propCode", "UNIVERSAL_PIECE");
                    jsonArray.put(jsonObject);
                }
                return RequestManager.requestString("alipay.antocean.ocean.h5.usePropByType",
                        "[{\"assetsDetails\":" + jsonArray + ",\"propCode\":\"UNIVERSAL_PIECE\",\"propType\":\"UNIVERSAL_PIECE\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"uniqueId\":\"" + getUniqueId() + "\"}]");
            }
        } catch (JSONException e) {
            Log.printStackTrace(e);
        }
        return null;
    }

    /**
     * 限时挑战
     */
    public static String createSeaAreaExtraCollect() {
        try {
            JSONObject param = new JSONObject();
            param.put("source", "chInfo_ch_appcenter__chsub_9patch");
            param.put("uniqueId", getUniqueId());

            JSONArray params = new JSONArray();
            params.put(param);

            return RequestManager.requestString(
                    "alipay.antocean.ocean.h5.createSeaAreaExtraCollect",
                    params.toString()
            );
        } catch (Exception e) {
            Log.printStackTrace("AntOceanRpcCall", e);
            return "{}";
        }
    }
}
