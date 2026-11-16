package fansirsqi.xposed.sesame.task.goldenbeans;

import fansirsqi.xposed.sesame.hook.RequestManager;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 金豆夺宝接口封装。
 * <p>
 * 金豆夺宝有两个互相独立的入口：芭芭农场与芝麻炼金。二者奖励共用，
 * 但请求体中的 bizType、source 与 sceneCode 各不相同，混用会被服务端拒绝；
 * 因此带 {@code Of} 后缀的方法用于显式指定入口，不带后缀的默认走农场入口。
 * <p>
 * 请求体统一为 {@code [{...}]} 数组，且必须带上 bizType、source 与 version。
 */
public class goldenbeansRpcCall {

    // ===== 入口常量 =====
    /** 芭芭农场入口 bizType */
    public static final String FARM_BIZ_TYPE = "MASTER";
    /** 芭芭农场入口 source */
    public static final String FARM_SOURCE = "babafarm";
    /** 芭芭农场入口任务场景 */
    public static final String FARM_TASK_SCENE_CODE = "GOLDEN_BEAN_MASTER_TASK";

    /** 芝麻炼金入口 bizType */
    public static final String ALCHEMY_BIZ_TYPE = "ZHIMA";
    /** 芝麻炼金入口 source */
    public static final String ALCHEMY_SOURCE = "lianjin";
    /** 芝麻炼金入口任务场景 */
    public static final String ALCHEMY_TASK_SCENE_CODE = "GOLDEN_BEAN_ZHIMA_LIST";

    public static final String VERSION = "20260803.01";

    // ===== 金豆商城（芭芭农场金豆罐兑换页） =====
    /** 商城场景码与主页任务场景不同，混用会被拒 */
    public static final String MALL_SCENE_CODE = "ANTORCHARD_JINDOU_MALL";
    /** 商品列表与兑换的 source */
    public static final String MALL_SOURCE = "MASTER";
    /** 订单查询的 source 与列表不同，取自官方页面调用 */
    private static final String MALL_ORDER_SOURCE = "antorchard";
    private static final String MALL_SUB_CHANNEL = "babafarm";
    /** 商城接口版本，与主页 VERSION 不同，勿混用 */
    public static final String MALL_VERSION = "20260901.01";
    /** 金猫矿工页面来源 */
    public static final String MINER_PAGE_SOURCE =
            "ch_url-https://render.alipay.com/p/yuyan/180020010001291350/index.html";
    /** 金豆乐园的 bizType 与 sceneCode 相同 */
    public static final String GAME_BIZ_TYPE = "GOLDENBEAN";
    /**
     * 金豆乐园游戏上报渠道：该场景必须使用 {@code "goldenbean"} 作为 action_finish_channel。
     * <p>渠道不对会导致游戏服接受上报（code=1）但支付宝侧权益不推进。
     */
    public static final String GAME_CHANNEL = "goldenbean";
    private static final String GAME_QUERY_VERSION = "10.8.20.8000";

    // ===== 任务与动作类型 =====
    /** 挖矿任务类型 */
    public static final String TASK_TYPE_MINING = "GOLDEN_BEAN_TASK_WAKUANG";
    /** 乐园任务类型 */
    public static final String TASK_TYPE_GAME_CENTER = "JINDOULEYUAN_TRIGGER";
    /** 换豆任务类型 */
    public static final String TASK_TYPE_EXCHANGE = "MANURE_EXCHANGE";
    /** 直接触发类动作 */
    public static final String ACTION_TYPE_TRIGGER = "TRIGGER";
    /** 乐园触发类动作 */
    public static final String ACTION_TYPE_GAME_CENTER = "GAMECENTER_TRIGGER";
    /** 营销弹窗点击动作 */
    public static final String TRIGGER_MARKETING_POPUP = "MARKETING_POPUP_CLICKED";

    private goldenbeansRpcCall() {
    }

    public interface Transport { String request(String method, String body); }
    private static volatile Transport testTransport;
    public static void setTransportForTest(Transport transport) { testTransport = transport; }
    private static String request(String method, JSONObject data) {
        String body = new JSONArray().put(data).toString();
        Transport test = testTransport;
        if (test != null) return test.request(method, body);
        GoldenBeansRuntime.check();
        String response = RequestManager.requestString(method, body, 1, 0);
        GoldenBeansRuntime.check();
        return response;
    }

    /** 主页查询（默认农场入口） */
    public static String home() throws Exception {
        return homeOf(FARM_BIZ_TYPE, FARM_SOURCE);
    }

    /** 主页查询（指定入口） */
    public static String homeOf(String bizType, String source) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("darwinSceneList", new JSONArray());
        params.put("source", source);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.index", params);
    }

    /** 数据同步（默认农场入口） */
    public static String pull(String... syncTypes) throws Exception {
        return pullOf(FARM_BIZ_TYPE, FARM_SOURCE, syncTypes);
    }

    /** 数据同步（指定入口） */
    public static String pullOf(String bizType, String source, String... syncTypes) throws Exception {
        JSONArray typeList = new JSONArray();
        for (String type : syncTypes) {
            typeList.put(type);
        }
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("source", source);
        params.put("syncTypeList", typeList);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.sync", params);
    }

    /** 数据同步（仅替换 source，保持农场入口 bizType；矿工场景使用） */
    public static String pullBySource(String source, String... syncTypes) throws Exception {
        JSONArray typeList = new JSONArray();
        for (String type : syncTypes) {
            typeList.put(type);
        }
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("source", source);
        params.put("syncTypeList", typeList);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.sync", params);
    }

    // ===== 金豆商城 =====
    /** 商城商品列表；响应 itemInfoVOList[].skuModelList[] 带 spuId/skuId/价格与当日可兑次数 */
    public static String mallItems(int startIndex, int pageSize) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("pageSize", pageSize);
        params.put("requestType", "RPC");
        params.put("sceneCode", MALL_SCENE_CODE);
        params.put("source", MALL_SOURCE);
        params.put("startIndex", startIndex);
        params.put("subChannel", MALL_SUB_CHANNEL);
        params.put("version", MALL_VERSION);
        return request("com.alipay.antiep.itemList", params);
    }

    /** 商城兑换；以 canBuy 判定是否成立，orderNo 用于事后核对 */
    public static String mallExchange(String spuId, String skuId) throws Exception {
        return mallExchange(spuId, skuId, UUID.randomUUID().toString());
    }
    public static String mallExchange(String spuId, String skuId, String requestId) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("requestId", requestId);
        params.put("sceneCode", MALL_SCENE_CODE);
        params.put("skuId", skuId);
        params.put("source", MALL_SOURCE);
        params.put("spuId", spuId);
        params.put("version", MALL_VERSION);
        return request("com.alipay.antcommonweal.exchange.h5.exchangeBenefit", params);
    }

    /** 商城兑换订单查询，响应 orderInfos[] 用于兑换后回查是否真的落单 */
    public static String mallOrders(int pageNum, int size) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("pageNum", pageNum);
        params.put("sceneCode", MALL_SCENE_CODE);
        params.put("size", size);
        params.put("source", MALL_ORDER_SOURCE);
        params.put("version", MALL_VERSION);
        return request("com.alipay.antcommonweal.exchange.h5.queryExchangeOrders", params);
    }

    /** 每日签到（默认农场入口） */
    public static String checkIn(String signKey) throws Exception {
        return checkInOf(FARM_BIZ_TYPE, FARM_SOURCE, signKey);
    }

    /** 每日签到（指定入口） */
    public static String checkInOf(String bizType, String source, String signKey) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("signKey", signKey);
        params.put("source", source);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.sign", params);
    }

    /** 触发任务动作（默认农场入口） */
    public static String fire(String taskId, String triggerType) throws Exception {
        return fireOf(FARM_BIZ_TYPE, FARM_SOURCE, taskId, triggerType);
    }

    /** 触发任务动作（指定入口） */
    public static String fireOf(String bizType, String source, String taskId, String triggerType)
            throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("source", source);
        params.put("taskId", taskId);
        params.put("triggerType", triggerType);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.trigger", params);
    }

    /** 提交任务完成（默认农场入口） */
    public static String submitTask(String taskType) throws Exception {
        return submitTaskOf(FARM_BIZ_TYPE, FARM_SOURCE, FARM_TASK_SCENE_CODE, taskType);
    }

    /** 提交任务完成（指定入口，sceneCode 随入口变化） */
    public static String submitTaskOf(String bizType, String source, String taskSceneCode, String taskType)
            throws Exception {
        return submitTaskOf(bizType, source, taskSceneCode, taskType, UUID.randomUUID().toString());
    }
    public static String submitTaskOf(String bizType, String source, String taskSceneCode, String taskType, String actionId) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("finishBusinessInfo", new JSONObject().put("bizType", bizType));
        params.put("outBizNo", actionId);
        params.put("sceneCode", taskSceneCode);
        params.put("source", source);
        params.put("taskType", taskType);
        params.put("version", VERSION);
        return request("com.alipay.antieptask.finishTaskantorchard", params);
    }

    /**
     * 另一种实现方案：乐园游戏类任务（{@code GOLDENBEAN_GAME_*} / {@code ZHIMA_youxi_*}）会被
     * {@code finishTaskantorchard} 以 400000040 拒绝，改走这条能做成。
     * 它的响应不可信（常回 102 而任务已生效），调用方必须回查任务列表核对。
     */
    public static String doFarmTask(String bizKey, String taskSceneCode) throws Exception {
        // 金豆各入口的 sceneCode 与 taskSceneCode 同值（见 submitTaskOf/claimAwardOf），故直接透传；
        // 兜底默认的庄园场景会被判"场景不合法"
        JSONObject params = new JSONObject();
        params.put("bizKey", bizKey); params.put("requestType", "RPC");
        params.put("sceneCode", taskSceneCode); params.put("taskSceneCode", taskSceneCode);
        params.put("source", "H5");
        params.put("version", VERSION);
        return request("com.alipay.antfarm.doFarmTask", params);
    }

    /** 领取任务奖励（默认农场入口） */
    public static String claimAward(String taskType) throws Exception {
        return claimAwardOf(FARM_BIZ_TYPE, FARM_SOURCE, FARM_TASK_SCENE_CODE, taskType);
    }

    /** 领取任务奖励（指定入口，sceneCode 随入口变化） */
    public static String claimAwardOf(String bizType, String source, String taskSceneCode, String taskType)
            throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizInfo", new JSONObject().put("bizType", bizType));
        params.put("bizType", bizType);
        params.put("ignoreLimit", true);
        params.put("sceneCode", taskSceneCode);
        params.put("source", source);
        params.put("taskType", taskType);
        params.put("version", VERSION);
        return request("com.alipay.antieptask.receiveTaskAwardantorchard", params);
    }

    /** 金猫矿工主页查询 */
    public static String minerHome() throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("source", MINER_PAGE_SOURCE);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.miner.index", params);
    }

    /** 金猫矿工抓取 */
    public static String grabBean(String grabResult, String itemId) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", FARM_BIZ_TYPE);
        params.put("grabId", UUID.randomUUID().toString());
        params.put("grabResult", grabResult);
        if (itemId != null && !itemId.isEmpty()) {
            params.put("itemId", itemId);
        }
        params.put("source", MINER_PAGE_SOURCE);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.miner.grab", params);
    }

    /** 金豆乐园游戏与权益列表查询 */
    public static String fetchGameList() throws Exception {
        JSONObject degrade = new JSONObject();
        degrade.put("deviceLevel", "high");
        degrade.put("platform", "Android");
        degrade.put("unityDeviceLevel", "high");
        JSONObject params = new JSONObject();
        params.put("bizType", GAME_BIZ_TYPE);
        params.put("commonDegradeFilterRequest", degrade);
        params.put("requestType", "RPC");
        params.put("sceneCode", GAME_BIZ_TYPE);
        params.put("source", FARM_SOURCE);
        params.put("version", GAME_QUERY_VERSION);
        return request("com.alipay.charitygamecenter.queryGameList", params);
    }

    /** 金豆乐园抽奖 */
    public static String drawLottery() throws Exception {
        JSONObject params = new JSONObject();
        params.put("batchDrawCount", 1);
        params.put("bizType", GAME_BIZ_TYPE);
        params.put("requestType", "RPC");
        params.put("sceneCode", GAME_BIZ_TYPE);
        params.put("source", FARM_SOURCE);
        params.put("version", VERSION);
        return request("com.alipay.charitygamecenter.drawGameCenterAward", params);
    }

    /**
     * 兑换金豆（默认农场入口，消耗肥料）。
     *
     * @param beanAmount 希望换到的金豆数量，而非被消耗的肥料数量
     */
    public static String exchangeBean(int beanAmount) throws Exception {
        return exchangeBeanOf(FARM_BIZ_TYPE, FARM_SOURCE, beanAmount);
    }

    /**
     * 兑换金豆（指定入口：农场入口消耗肥料，芝麻炼金入口消耗芝麻粒）。
     *
     * @param beanAmount 希望换到的金豆数量，而非被消耗的肥料或芝麻粒数量
     */
    public static String exchangeBeanOf(String bizType, String source, int beanAmount) throws Exception {
        JSONObject params = new JSONObject();
        params.put("bizType", bizType);
        params.put("exchangeBeanAmount", beanAmount);
        params.put("source", source);
        params.put("version", VERSION);
        return request("com.alipay.goldenbean.manureExchange", params);
    }
}
