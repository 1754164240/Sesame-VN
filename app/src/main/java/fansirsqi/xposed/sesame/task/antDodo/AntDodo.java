package fansirsqi.xposed.sesame.task.antDodo;
import static fansirsqi.xposed.sesame.entity.OtherEntityProvider.listPropGroupOptions;

import com.fasterxml.jackson.core.type.TypeReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import fansirsqi.xposed.sesame.entity.AlipayUser;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.hook.RequestManager;
import fansirsqi.xposed.sesame.model.ModelFields;
import fansirsqi.xposed.sesame.model.ModelGroup;
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.ChoiceModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField;
import fansirsqi.xposed.sesame.util.DataStore;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskStatus;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.ResChecker;
import fansirsqi.xposed.sesame.util.TimeUtil;
public class AntDodo extends ModelTask {

    /**
     * 仅限 AntDodo 内部使用的道具组常量定义
     */
    public interface PropGroupType {
        /** 当前图鉴抽卡券 🎴 */
        String COLLECT_ANIMAL = "COLLECT_ANIMAL";
        /** 好友卡抽卡券 👥 */
        String ADD_COLLECT_TO_FRIEND_LIMIT = "ADD_COLLECT_TO_FRIEND_LIMIT";
        /** 万能卡 🃏 */
        String UNIVERSAL_CARD = "UNIVERSAL_CARD";
    }

    private static final String TAG = AntDodo.class.getSimpleName();
    @Override
    public String getName() {
        return "神奇物种";
    }
    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }
    @Override
    public String getIcon() {
        return "AntDodo.png";
    }
    private BooleanModelField collectToFriend;
    private ChoiceModelField collectToFriendType;
    private SelectModelField collectToFriendList;
    private SelectModelField sendFriendCard;

    private SelectModelField usepropGroup;  //道具使用类型
    private ChoiceModelField usePropUNIVERSALCARDType;         //万能卡使用方法

    private BooleanModelField autoGenerateBook;
    private BooleanModelField scratchCard;
    private ChoiceModelField useCollectTimingType;
    private BooleanModelField universalDetailedFilter;
    private ChoiceModelField universalBookStatus, universalCollectedStatus, universalMedalStatus, universalLevel;
    private BooleanModelField universalPreferNeverOwned;
    private BooleanModelField giftDetailedFilter;
    private ChoiceModelField giftBookStatus, giftCollectedStatus, giftMedalStatus, giftLevel;
    private BooleanSupplier executionGuard = () -> false;
    private String executionUid;
    private final Set<String> scratchedThisRun = new LinkedHashSet<>();
    private final Set<String> propsAttemptedThisRun = new LinkedHashSet<>();
    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(collectToFriend = new BooleanModelField("collectToFriend", "帮抽卡 | 开启", false));
        modelFields.addField(collectToFriendType = new ChoiceModelField("collectToFriendType", "帮抽卡 | 动作", CollectToFriendType.COLLECT, CollectToFriendType.nickNames));
        modelFields.addField(collectToFriendList = new SelectModelField("collectToFriendList", "帮抽卡 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(sendFriendCard = new SelectModelField("sendFriendCard", "送卡片好友列表(当前图鉴所有卡片)", new LinkedHashSet<>(), AlipayUser::getList));

        // 道具组类型：使用你刚刚定义的列表提供者
        modelFields.addField(usepropGroup = new SelectModelField("usepropGroup", "使用道具类型", new LinkedHashSet<>(), listPropGroupOptions()));

        modelFields.addField(usePropUNIVERSALCARDType = new ChoiceModelField("usePropUNIVERSALCARDType", "万能卡 | 使用方式", UniversalCardUseType.EXCLUDE_CURRENT, UniversalCardUseType.nickNames));
        modelFields.addField(autoGenerateBook = new BooleanModelField("autoGenerateBook", "自动合成图鉴", false));
        modelFields.addField(scratchCard = new BooleanModelField("scratchCard", "物种V2 | 自动刮卡", false));
        modelFields.addField(useCollectTimingType = new ChoiceModelField("useCollectTimingType", "抽卡道具 | 使用时机", 0, new String[]{"每天使用", "专辑最后一天"}));
        modelFields.addField(universalDetailedFilter = new BooleanModelField("universalDetailedFilter", "万能卡 | 启用精细筛选", false));
        modelFields.addField(universalBookStatus = bookStatusField("universalBookStatus", "万能卡 | 图鉴状态", 1));
        modelFields.addField(universalCollectedStatus = collectedStatusField("universalCollectedStatus", "万能卡 | 收集状态"));
        modelFields.addField(universalMedalStatus = medalStatusField("universalMedalStatus", "万能卡 | 勋章状态"));
        modelFields.addField(universalLevel = levelField("universalLevel", "万能卡 | 最低等级", 2));
        modelFields.addField(universalPreferNeverOwned = new BooleanModelField("universalPreferNeverOwned", "万能卡 | 优先未曾获得", false));
        modelFields.addField(giftDetailedFilter = new BooleanModelField("giftDetailedFilter", "赠卡 | 启用全图鉴精细筛选", false));
        modelFields.addField(giftBookStatus = bookStatusField("giftBookStatus", "赠卡 | 图鉴状态", 2));
        modelFields.addField(giftCollectedStatus = collectedStatusField("giftCollectedStatus", "赠卡 | 收集状态"));
        modelFields.addField(giftMedalStatus = medalStatusField("giftMedalStatus", "赠卡 | 勋章状态"));
        modelFields.addField(giftLevel = levelField("giftLevel", "赠卡 | 最低等级", 0));
        return modelFields;
    }

    private static ChoiceModelField bookStatusField(String id, String name, int value) {
        return new ChoiceModelField(id, name, value, new String[]{"全部图鉴", "往期图鉴", "本期图鉴"});
    }
    private static ChoiceModelField collectedStatusField(String id, String name) {
        return new ChoiceModelField(id, name, 0, new String[]{"全部状态", "未完成收集", "已完成收集"});
    }
    private static ChoiceModelField medalStatusField(String id, String name) {
        return new ChoiceModelField(id, name, 0, new String[]{"全部状态", "未能合成", "可以合成", "已经合成"});
    }
    private static ChoiceModelField levelField(String id, String name, int value) {
        return new ChoiceModelField(id, name, value, new String[]{"普通", "稀有", "神奇"});
    }

    private boolean active() {
        return executionGuard.getAsBoolean() && !Thread.currentThread().isInterrupted()
                && !ApplicationHook.offline && !RequestManager.isVerificationPaused()
                && executionUid != null && !executionUid.isEmpty() && executionUid.equals(UserMap.INSTANCE.getCurrentUid());
    }
    @Override
    protected void runJava() {
        executionGuard = captureExecutionGuard();
        executionUid = UserMap.INSTANCE.getCurrentUid();
        scratchedThisRun.clear();
        propsAttemptedThisRun.clear();
        try {
            if (!active()) return;
            Log.record(TAG,"执行开始-" + getName());
            receiveTaskAward();
            if (active()) propList();
            if (active()) collect();
            if (giftDetailedFilter.getValue() && active()) giftFilteredCards();
            if (collectToFriend.getValue() && active()) {
                collectToFriend();
            }
            if(autoGenerateBook.getValue() && active()){
                autoGenerateBook();//自动 兑换
            }
            if (scratchCard.getValue() && active()) scratchPendingCards();
        } catch (Throwable t) {
            Log.printStackTrace(TAG,"start Dodo.run err:", t);
        }finally {
            Log.record(TAG,"执行结束-" + getName());
        }
    }
    /*
     * 神奇物种
     */
    private boolean lastDay(String endDate) {
        long timeStep = System.currentTimeMillis();
        long endTimeStep = TimeUtil.timeToStamp(endDate);
        return timeStep < endTimeStep && (endTimeStep - timeStep) < 86400000L;
    }
    public boolean in8Days(String endDate) {
        long timeStep = System.currentTimeMillis();
        long endTimeStep = TimeUtil.timeToStamp(endDate);
        return timeStep < endTimeStep && (endTimeStep - timeStep) < 691200000L;
    }
    private void collect() {
        try {
            JSONObject jo = new JSONObject(AntDodoRpcCall.queryAnimalStatus());
            if (!active()) return;
            if (ResChecker.checkRes(TAG,jo)) {
                JSONObject data = jo.getJSONObject("data");
                if (data.getBoolean("collect")) {
                    Log.record(TAG,"神奇物种卡片今日收集完成！");
                } else {
                    collectAnimalCard();
                }
            } else {
                Log.record(TAG, "collect错误"+jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "AntDodo Collect err:",t);
        }
    }
    private void collectAnimalCard() {
        if (!active()) return;
        try {
            JSONObject jo = new JSONObject(AntDodoRpcCall.homePage());
            if (!active()) return;
            if (ResChecker.checkRes(TAG,jo)) {
                JSONObject data = jo.getJSONObject("data");
                JSONObject animalBook = data.getJSONObject("animalBook");
                String bookId = animalBook.getString("bookId");
                String endDate = animalBook.getString("endDate") + " 23:59:59";
                receiveTaskAward();
                if (!in8Days(endDate) || lastDay(endDate))
                    propList();
                JSONArray ja = data.getJSONArray("limit");
                int index = -1;
                for (int i = 0; i < ja.length(); i++) {
                    jo = ja.getJSONObject(i);
                    if ("DAILY_COLLECT".equals(jo.getString("actionCode"))) {
                        index = i;
                        break;
                    }
                }
                Set<String> set = sendFriendCard.getValue();
                if (index >= 0) {
                    int leftFreeQuota = jo.getInt("leftFreeQuota");
                    for (int j = 0; j < Math.min(leftFreeQuota, 100) && active(); j++) {
                        jo = new JSONObject(AntDodoRpcCall.collect());
                        if (!active()) return;
                        if (ResChecker.checkRes(TAG,jo)) {
                            data = jo.getJSONObject("data");
                            scratchNewCard(data);
                            JSONObject animal = data.getJSONObject("animal");
                            String ecosystem = animal.getString("ecosystem");
                            String name = animal.getString("name");
                            Log.forest("神奇物种🦕[" + ecosystem + "]#" + name);
                            if (!set.isEmpty() && !giftDetailedFilter.getValue()) {
                                for (String userId : set) {
                                    if (!UserMap.INSTANCE.getCurrentUid().equals(userId)) {
                                        int fantasticStarQuantity = animal.optInt("fantasticStarQuantity", 0);
                                        if (fantasticStarQuantity == 3) {
                                            sendCard(animal, userId);
                                        }
                                        break;
                                    }
                                }
                            }
                        } else {
                            Log.record(TAG,"collectAnimalCard错误"+ jo.getString("resultDesc"));
                        }
                    }
                }
                if (!set.isEmpty() && !giftDetailedFilter.getValue()) {
                    for (String userId : set) {
                        if (!UserMap.INSTANCE.getCurrentUid().equals(userId)) {
                            sendAntDodoCard(bookId, userId);
                            break;
                        }
                    }
                }
            } else {
                Log.record(TAG, "collectAnimalCard错误2 "+ jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG,"AntDodo CollectAnimalCard err:",t);
        }
    }
    /**
     * 神奇物种任务
     */
    private void receiveTaskAward() {
        try {
            // 获取不能完成的任务列表
            Set<String> presetBad = new LinkedHashSet<>(List.of("HELP_FRIEND_COLLECT"));
            TypeReference<Set<String>> typeRef = new TypeReference<>() {};
            Set<String> badTaskSet = DataStore.INSTANCE.getOrCreate("badDodoTaskList", typeRef);
            if (badTaskSet.isEmpty()) {
                badTaskSet.addAll(presetBad);
                DataStore.INSTANCE.put("badDodoTaskList", badTaskSet);
            }
            for (int pass = 0; pass < 20 && active(); pass++) {
                boolean doubleCheck = false;
                String response = AntDodoRpcCall.taskList(); // 调用任务列表接口
                JSONObject jsonResponse = new JSONObject(response); // 解析响应为 JSON 对象
                if (!active()) return;
                // 检查响应结果码是否成功
                if (!ResChecker.checkRes(TAG, jsonResponse)) {
                    Log.record(TAG, "查询任务列表失败：" + jsonResponse.getString("resultDesc"));
                    break;
                }
                // 获取任务组信息列表
                JSONArray taskGroupInfoList = jsonResponse.getJSONObject("data").optJSONArray("taskGroupInfoList");
                if (taskGroupInfoList == null) return; // 如果任务组为空则返回
                // 遍历每个任务组
                for (int i = 0; i < taskGroupInfoList.length(); i++) {
                    JSONObject antDodoTask = taskGroupInfoList.getJSONObject(i);
                    JSONArray taskInfoList = antDodoTask.getJSONArray("taskInfoList"); // 获取任务信息列表
                    // 遍历每个任务
                    for (int j = 0; j < taskInfoList.length() && active(); j++) {
                        JSONObject taskInfo = taskInfoList.getJSONObject(j);
                        JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo"); // 获取任务基本信息
                        JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo")); // 获取业务信息
                        String taskType = taskBaseInfo.getString("taskType"); // 获取任务类型
                        String taskTitle = bizInfo.optString("taskTitle", taskType); // 获取任务标题
                        String awardCount = bizInfo.optString("awardCount", "1"); // 获取奖励数量
                        String sceneCode = taskBaseInfo.getString("sceneCode"); // 获取场景代码
                        String taskStatus = taskBaseInfo.getString("taskStatus"); // 获取任务状态
                        // 如果任务已完成，领取任务奖励
                        if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                            JSONObject joAward = new JSONObject(
                                    AntDodoRpcCall.receiveTaskAward(sceneCode, taskType)); // 领取奖励请求
                            if (!active()) return;
                            if (joAward.optBoolean("success")) {
                                doubleCheck = true;
                                Log.forest("任务奖励🎖️[" + taskTitle + "]#" + awardCount + "个");
                            } else {
                                Log.record(TAG,"领取失败，" + response); // 记录领取失败信息
                            }
                            Log.record(TAG,joAward.toString()); // 打印奖励响应
                        }
                        // 如果任务待完成，处理特定类型的任务
                        else if (TaskStatus.TODO.name().equals(taskStatus)) {
                            if (!badTaskSet.contains(taskType)) {
                                // 尝试完成任务
                                JSONObject joFinishTask = new JSONObject(
                                        AntDodoRpcCall.finishTask(sceneCode, taskType)); // 完成任务请求
                                if (!active()) return;
                                if (joFinishTask.optBoolean("success")) {
                                    Log.forest("物种任务🧾️[" + taskTitle + "]");
                                    doubleCheck = true;
                                } else {
                                    Log.record(TAG,"完成任务失败，" + taskTitle); // 记录完成任务失败信息
                                    badTaskSet.add(taskType);
                                    DataStore.INSTANCE.put("badDodoTaskList", badTaskSet);
                                }

                            }
                        }
                        GlobalThreadPools.sleepCompat(500);
                    }
                }
                if (!doubleCheck) break;
            }
        } catch (JSONException e) {
            Log.printStackTrace(TAG,"神奇物种 JSON解析错误: " + e.getMessage(),e);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "AntDodo ReceiveTaskAward 错误:",t); // 打印异常栈
        }
    }


    public void propList() {
        try {
            if (!active() || usepropGroup.getValue().isEmpty()) return;
            String response = AntDodoRpcCall.propList();
            if (!DodoCardPolicy.accepted(response) || !active()) return;
            JSONArray props = new JSONObject(response).getJSONObject("data").optJSONArray("propList");
            long endTime = 0L;
            int collectedCount = 0;
            int totalCount = 0;
            if (!active()) return;
            JSONObject home = new JSONObject(AntDodoRpcCall.homePage());
            if (!active()) return;
            if (DodoCardPolicy.accepted(home.toString())) {
                JSONObject data = home.optJSONObject("data");
                JSONObject book = data == null ? null : data.optJSONObject("animalBook");
                collectedCount = data == null ? 0 : data.optInt("curCollectionCategoryCount");
                totalCount = book == null ? 0 : book.optInt("totalCount");
                if (book != null && !book.optString("endDate").isEmpty()) {
                    endTime = TimeUtil.timeToStamp(book.optString("endDate") + " 23:59:59");
                }
            }
            int actions = 0;
            for (int i = 0; props != null && i < props.length() && active(); i++) {
                JSONObject prop = props.optJSONObject(i);
                if (prop == null) continue;
                JSONObject config = prop == null ? null : prop.optJSONObject("propConfig");
                String group = config == null ? "" : config.optString("propGroup");
                if (!usepropGroup.getValue().contains(group) || prop.optInt("holdsNum", 0) <= 0) continue;
                if (PropGroupType.COLLECT_ANIMAL.equals(group) && totalCount > 0 && collectedCount >= totalCount) continue;
                if (PropGroupType.COLLECT_ANIMAL.equals(group)
                        && !DodoCardPolicy.shouldUseDraw(useCollectTimingType.getValue(), endTime, System.currentTimeMillis())) continue;
                JSONArray ids = prop.optJSONArray("propIdList");
                for (int j = 0; ids != null && j < Math.min(ids.length(), prop.optInt("holdsNum")) && actions < 200 && propsAttemptedThisRun.size() < 200 && active(); j++) {
                    String id = ids.optString(j);
                    if (PropGroupType.COLLECT_ANIMAL.equals(group) && totalCount > 0 && collectedCount >= totalCount) break;
                    if (id.isEmpty() || !propsAttemptedThisRun.add(id)) continue;
                    String animalId = null;
                    if (PropGroupType.UNIVERSAL_CARD.equals(group)) {
                        animalId = universalDetailedFilter.getValue() ? filteredUniversalTarget() : getTargetAnimalIdForUniversalCard();
                        if (animalId.isEmpty()) break;
                    }
                    if (!active()) return;
                    String used = AntDodoRpcCall.consumePropOnce(id, prop.optString("propType"), animalId);
                    actions++;
                    if (!DodoCardPolicy.accepted(used)) break;
                    if (!active()) return;
                    JSONObject data = new JSONObject(used).optJSONObject("data");
                    JSONObject result = data == null ? null : data.optJSONObject("useResult");
                    if (result != null) {
                        scratchNewCard(result);
                        JSONObject detail = result.optJSONObject("collectDetail");
                        if (PropGroupType.COLLECT_ANIMAL.equals(group) && detail != null && detail.optBoolean("newCard")) collectedCount++;
                    }
                    Log.forest("神奇物种🦕使用道具[" + group + "]");
                    GlobalThreadPools.sleepCompat(500L);
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "神奇物种道具处理异常", t);
        }
    }

    private String filteredUniversalTarget() throws JSONException {
        JSONArray candidates = new JSONArray();
        JSONArray books = getAllBookList(this::active);
        for (int i = 0; i < books.length() && active(); i++) {
            JSONObject book = books.optJSONObject(i);
            if (!DodoCardPolicy.matchesBook(book, universalBookStatus.getValue(), universalCollectedStatus.getValue(), universalMedalStatus.getValue())) continue;
            JSONObject result = book.optJSONObject("animalBookResult");
            if (result == null || result.optString("bookId").isEmpty()) continue;
            String detail = AntDodoRpcCall.queryBookInfo(result.optString("bookId"));
            if (!active()) return "";
            if (!DodoCardPolicy.accepted(detail)) continue;
            JSONObject data = new JSONObject(detail).optJSONObject("data");
            JSONArray cards = data == null ? null : data.optJSONArray("animalForUserList");
            for (int j = 0; cards != null && j < cards.length(); j++) candidates.put(cards.opt(j));
        }
        JSONObject target = DodoCardPolicy.selectUniversal(candidates, universalLevel.getValue() + 1, universalPreferNeverOwned.getValue());
        return target == null ? "" : target.optString("animalId");
    }

    private void giftFilteredCards() throws JSONException {
        String friend = null;
        for (String uid : sendFriendCard.getValue()) {
            if (!uid.equals(executionUid)) { friend = uid; break; }
        }
        if (friend == null) return;
        JSONArray books = getAllBookList(this::active);
        int actions = 0;
        for (int i = 0; i < books.length() && active() && actions < 200; i++) {
            JSONObject book = books.optJSONObject(i);
            if (!DodoCardPolicy.matchesBook(book, giftBookStatus.getValue(), giftCollectedStatus.getValue(), giftMedalStatus.getValue())) continue;
            JSONObject result = book.optJSONObject("animalBookResult");
            if (result == null || result.optString("bookId").isEmpty()) continue;
            String detail = AntDodoRpcCall.queryBookInfo(result.optString("bookId"));
            if (!active()) return;
            if (!DodoCardPolicy.accepted(detail)) continue;
            JSONObject data = new JSONObject(detail).optJSONObject("data");
            for (JSONObject card : DodoCardPolicy.giftCards(data == null ? null : data.optJSONArray("animalForUserList"), giftLevel.getValue() + 1)) {
                int count = Math.min(200 - actions, card.getJSONObject("collectDetail").optInt("count"));
                int previousCount = card.getJSONObject("collectDetail").optInt("count");
                for (int j = 0; j < count && active(); j++) {
                    JSONObject animal = card.getJSONObject("animal");
                    if (!sendCard(animal, friend, true)) return;
                    actions++;
                    GlobalThreadPools.sleepCompat(500L);
                    if (!active()) return;
                    String confirmed = AntDodoRpcCall.queryBookInfo(result.optString("bookId"));
                    if (!active() || !DodoCardPolicy.accepted(confirmed)) return;
                    JSONObject confirmedData = new JSONObject(confirmed).optJSONObject("data");
                    JSONArray confirmedCards = confirmedData == null ? null : confirmedData.optJSONArray("animalForUserList");
                    int afterCount = -1;
                    for (int k = 0; confirmedCards != null && k < confirmedCards.length(); k++) {
                        JSONObject after = confirmedCards.optJSONObject(k);
                        JSONObject afterAnimal = after == null ? null : after.optJSONObject("animal");
                        JSONObject afterDetail = after == null ? null : after.optJSONObject("collectDetail");
                        if (afterAnimal != null && afterDetail != null && animal.optString("animalId").equals(afterAnimal.optString("animalId"))) {
                            afterCount = afterDetail.optInt("count", -1);
                            break;
                        }
                    }
                    if (afterCount < 0 || afterCount >= previousCount) return;
                    previousCount = afterCount;
                    if (afterCount == 0) break;
                }
            }
        }
    }

    private void scratchPendingCards() throws JSONException {
        if (!active()) return;
        String response = AntDodoRpcCall.homePage();
        if (!DodoCardPolicy.accepted(response)) return;
        JSONObject data = new JSONObject(response).optJSONObject("data");
        if (data == null || !data.optBoolean("showSpeciesV2")) return;
        JSONArray collections = data.optJSONArray("curCollection");
        for (int i = 0; collections != null && i < collections.length() && active(); i++) scratchNewCard(collections.optJSONObject(i));
    }

    private void scratchNewCard(JSONObject data) throws JSONException {
        if (!scratchCard.getValue() || !active() || data == null) return;
        JSONObject animal = data.optJSONObject("animal");
        for (JSONObject card : DodoCardPolicy.scratchCards(data)) {
            if (!active() || scratchedThisRun.size() >= 200) return;
            String id = card.optString("cardInstanceId");
            String key = "antDodo::scratch::" + animal.optString("bookId") + "::" + id;
            if (!scratchedThisRun.add(id) || Status.hasFlagToday(key)) continue;
            String response = AntDodoRpcCall.scratchCard(animal.optString("animalId"), animal.optString("bookId"), id);
            if (!DodoCardPolicy.accepted(response)) continue;
            if (!active()) return;
            Status.setFlagToday(key);
            JSONObject reward = new JSONObject(response).optJSONObject("data");
            Log.forest("神奇物种🦕刮卡成功[" + animal.optString("name") + "]获得" + (reward == null ? 0 : reward.optInt("energyGram")) + "g能量");
            GlobalThreadPools.sleepCompat(500L);
        }
    }

    /**
     * 发送神奇物种卡片
     * @param bookId 卡片图鉴ID
     * @param targetUser 目标用户ID
     */
    private void sendAntDodoCard(String bookId, String targetUser) {
        if (!active()) return;
        try {
            JSONObject jo = new JSONObject(AntDodoRpcCall.queryBookInfo(bookId));
            if (!active()) return;
            if (ResChecker.checkRes(TAG,jo)) {
                JSONArray animalForUserList = jo.getJSONObject("data").optJSONArray("animalForUserList");
                for (int i = 0; i < Objects.requireNonNull(animalForUserList).length() && active(); i++) {
                    JSONObject animalForUser = animalForUserList.getJSONObject(i);
                    int count = animalForUser.getJSONObject("collectDetail").optInt("count");
                    if (count <= 0)
                        continue;
                    JSONObject animal = animalForUser.getJSONObject("animal");
                    for (int j = 0; j < Math.min(count, 200) && active(); j++) {
                        if (!sendCard(animal, targetUser)) return;
                        GlobalThreadPools.sleepCompat(500L);
                    }
                }
            }
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "AntDodo SendAntDodoCard err:",th);
        }
    }
    private boolean sendCard(JSONObject animal, String targetUser) {
        return sendCard(animal, targetUser, false);
    }
    private boolean sendCard(JSONObject animal, String targetUser, boolean singleAttempt) {
        if (!active() || targetUser.equals(executionUid)) return false;
        try {
            String animalId = animal.getString("animalId");
            String ecosystem = animal.getString("ecosystem");
            String name = animal.getString("name");
            JSONObject jo = new JSONObject(singleAttempt ? AntDodoRpcCall.socialOnce(animalId, targetUser) : AntDodoRpcCall.social(animalId, targetUser));
            if (!active()) return false;
            if (ResChecker.checkRes(TAG,jo)) {
                Log.forest("赠送卡片🦕[" + UserMap.getMaskName(targetUser) + "]#" + ecosystem + "-" + name);
                return true;
            } else {
                Log.record(TAG, "sendCard错误"+jo.getString("resultDesc"));
            }
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "AntDodo SendCard err:",th);
        }
        return false;
    }
    private void collectToFriend() {
        if (!active()) return;
        try {
            JSONObject jo = new JSONObject(AntDodoRpcCall.queryFriend());
            if (!active()) return;
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "神奇物种帮好友抽卡失败："+jo.getString("resultDesc"));
                return;
            }

            // 获取可用次数
            int count = 0;
            JSONArray limitList = jo.getJSONObject("data").getJSONObject("extend").getJSONArray("limit");
            for (int i = 0; i < limitList.length(); i++) {
                JSONObject limit = limitList.getJSONObject(i);
                if ("COLLECT_TO_FRIEND".equals(limit.getString("actionCode"))) {
                    // 检查是否有开始时间限制
                    if (limit.has("startTime") && limit.getLong("startTime") > System.currentTimeMillis()) {
                        Log.record("神奇物种🦕帮好友抽卡未到开放时间: " + limit.getString("startTimeStr"));
                        return;
                    }
                    count = limit.getInt("leftLimit");
                    break;
                }
            }

            if (count <= 0) {
                Log.record("神奇物种🦕帮好友抽卡次数已用完");
                return;
            }

            // 遍历好友列表
            JSONArray friendList = jo.getJSONObject("data").getJSONArray("friends");
            for (int i = 0; i < friendList.length() && count > 0 && active(); i++) {
                JSONObject friend = friendList.getJSONObject(i);

                // 跳过今日已帮助的好友
                if (friend.getBoolean("dailyCollect")) {
                    continue;
                }

                String userId = friend.getString("userId");

                // 判断是否应该帮助该好友
                boolean inList = collectToFriendList.getValue().contains(userId);
                boolean shouldCollect = (collectToFriendType.getValue() == CollectToFriendType.COLLECT) ? inList : !inList;

                if (!shouldCollect) {
                    continue;
                }

                // 执行抽卡
                jo = new JSONObject(AntDodoRpcCall.collecttarget(userId));
                if (!active()) return;
                if (ResChecker.checkRes(TAG, jo)) {
                    String ecosystem = jo.getJSONObject("data").getJSONObject("animal").getString("ecosystem");
                    String name = jo.getJSONObject("data").getJSONObject("animal").getString("name");
                    String userName = UserMap.getMaskName(userId);
                    Log.forest("神奇物种🦕帮好友[" + userName + "]抽卡[" + ecosystem + "]#" + name);
                    count--;
                } else {
                    Log.record(TAG, "collecttarget错误"+jo.getString("resultDesc"));
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "AntDodo CollectHelpFriend err:",t);
        }
    }


    /**
     * 辅助逻辑：获取万能卡要兑换的精准动物ID
     */
    private String getTargetAnimalIdForUniversalCard() {
        try {
            JSONArray allBooks = getAllBookList(this::active);
            if (allBooks == null || allBooks.length() == 0) {
                Log.record(TAG, "万能卡：未获取到任何图鉴数据");
                return "";
            }

            String targetBookId = "";
            int strategy = usePropUNIVERSALCARDType.getValue();

            String currentDoingBookId = "";
            String bestOtherBookId = "";
            double maxOtherRate = -1.0;

            String bestOverallBookId = "";
            double maxOverallRate = -1.0;

            for (int i = 0; i < allBooks.length() && active(); i++) {
                JSONObject book = allBooks.optJSONObject(i); // 使用 opt 防止 null
                if (book == null || isBookFinished(book)) continue;

                JSONObject result = book.optJSONObject("animalBookResult");
                if (result == null) continue;

                String bookId = result.optString("bookId");
                String status = book.optString("bookStatus");

                // --- 进度解析与计算 ---
                String prog = book.optString("collectProgress", "0/0");
                double rate = 0;
                try {
                    String[] p = prog.split("/");
                    if (p.length == 2) {
                        double current = Double.parseDouble(p[0]);
                        double total = Double.parseDouble(p[1]);
                        if (total > 0) {
                            rate = current / total;
                        }
                    }
                } catch (Exception ignored) {}

                // --- 策略分类收集 ---
                // 1. 识别当前正在进行的 (DOING)
                if ("DOING".equals(status)) {
                    currentDoingBookId = bookId;
                } else {
                    // 2. 识别非当前图鉴中进度最高的
                    if (rate > maxOtherRate) {
                        maxOtherRate = rate;
                        bestOtherBookId = bookId;
                    }
                }

                // 3. 识别全局进度最高的
                if (rate > maxOverallRate) {
                    maxOverallRate = rate;
                    bestOverallBookId = bookId;
                }
            }

            // --- 逻辑分支匹配 ---
            if (strategy == UniversalCardUseType.EXCLUDE_CURRENT) {
                targetBookId = bestOtherBookId;
                Log.record(TAG, "万能卡策略 [排除当前]: 选中非DOING最高进度图鉴 " + targetBookId);
            }
            else if (strategy == UniversalCardUseType.PRIORITY_MAX_PROGRESS) {
                targetBookId = bestOverallBookId;
                Log.record(TAG, "万能卡策略 [进度优先]: 选中全局最高进度图鉴 " + targetBookId);
            }
            else {
                // 模式：所有。优先进行中，进行中已满则选最高进度
                targetBookId = !currentDoingBookId.isEmpty() ? currentDoingBookId : bestOverallBookId;
                Log.record(TAG, "万能卡策略 [全部]: 优先进行中图鉴 " + targetBookId);
            }

            if (targetBookId.isEmpty()) return "";

            // --- 查询具体缺失卡片 ---
            if (!active()) return "";
            String detailJson = AntDodoRpcCall.queryBookInfo(targetBookId);
            JSONObject detailObj = new JSONObject(detailJson);

            // 增加对 detail 接口返回结果的校验
            if (detailObj.optBoolean("success", false) || "SUCCESS".equals(detailObj.optString("resultCode"))) {
                JSONObject data = detailObj.optJSONObject("data");
                JSONArray animals = (data != null) ? data.optJSONArray("animalForUserList") : null;

                if (animals != null) {
                    for (int i = 0; i < animals.length(); i++) {
                        JSONObject item = animals.optJSONObject(i);
                        if (item == null) continue;

                        JSONObject collectDetail = item.optJSONObject("collectDetail");
                        // 只有 collect 为 false 才说明是缺的
                        if (collectDetail != null && !collectDetail.optBoolean("collect", false)) {
                            JSONObject animalInfo = item.optJSONObject("animal");
                            if (animalInfo != null) {
                                String animalId = animalInfo.optString("animalId");
                                String name = animalInfo.optString("name");
                                Log.record(TAG, "万能卡目标锁定: " + name + " (" + animalId + ")");
                                return animalId;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.record(TAG, "万能卡逻辑执行失败: " + e.getMessage());
        }
        return "";
    }


    /**
     * 判断某个图鉴是否已经“完成” (不需要再投入万能卡)
     */
    private static boolean isBookFinished(JSONObject book) {
        if (book == null) return true;

        // 1. 优先判断合成状态：如果已经可以合成或者已经合成，则认为该图鉴已完成
        String medalStatus = book.optString("medalGenerationStatus");
        if ("CAN_GENERATE".equals(medalStatus) || "GENERATED".equals(medalStatus)) {
            return true;
        }

        // 2. 判断数字进度：例如 "10/10"
        String progress = book.optString("collectProgress", "");
        if (progress.contains("/")) {
            try {
                String[] parts = progress.split("/");
                if (parts.length == 2) {
                    int current = Integer.parseInt(parts[0].trim());
                    int total = Integer.parseInt(parts[1].trim());
                    return current >= total; // 只要现有的不小于总数，就不需要万能卡
                }
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }


    /* 获取所有图鉴列表*/
    /**
     * 获取完整的图鉴数组 (自动处理翻页合并)
     * @return 包含所有图鉴对象的 JSONArray
     *
     * [
     *   {
     *     "animalBookResult": {
     *       "bookId": "dxmlyBook",
     *       "ecosystem": "东喜马拉雅高山森林生态系统",
     *       "name": "东喜马拉雅高山森林生态系统",
     *       "totalCount": 10,
     *       "magicCount": 1,
     *       "rareCount": 2,
     *       "commonCount": 7
     *       // ..
     *     },
     *     "bookStatus": "END",
     *     "bookCollectedStatus": "NOT_COMPLETED",
     *     "collectProgress": "1/10",
     *     "hasRedDot": false
     *   },
     *   {
     *     "animalBookResult": {
     *       "bookId": "zhbhtbhxcr202503",
     *       "name": "当前正在进行的某个图鉴",
     *       "totalCount": 10
     *       // ...
     *     },
     *     "bookStatus": "GOING",
     *     "bookCollectedStatus": "NOT_COMPLETED",
     *     "collectProgress": "5/10",
     *     "hasRedDot": true
     *   }
     *   // ...
     * ]
     */

    public static JSONArray getAllBookList() {
        return getAllBookList(() -> !ApplicationHook.offline && !RequestManager.isVerificationPaused());
    }

    private static JSONArray getAllBookList(BooleanSupplier guard) {
        JSONArray allBooks = new JSONArray();
        String pageStart = null; // 首页传 null
        boolean hasMore = true;

        try {
            Set<String> pageTokens = new LinkedHashSet<>();
            for (int page = 0; page < 50 && hasMore && guard.getAsBoolean(); page++) {
                // 调用上面修改后的接口
                String res = AntDodoRpcCall.queryBookList(64, pageStart);
                if (!guard.getAsBoolean()) break;
                JSONObject jo = new JSONObject(res);

                if (!ResChecker.checkRes(TAG,jo)) {
                    Log.error(TAG, "queryBookList 失败: " + jo.optString("resultDesc"));
                    break;
                }

                JSONObject data = jo.optJSONObject("data");
                if (data == null) break;

                // 1. 提取并合并数据
                JSONArray currentList = data.optJSONArray("bookForUserList");
                if (currentList != null) {
                    for (int i = 0; i < currentList.length(); i++) {
                        allBooks.put(currentList.get(i));
                    }
                }

                // 2. 判断翻页逻辑
                hasMore = data.optBoolean("hasMore", false);
                pageStart = data.optString("nextPageStart", null);

                // 如果没有更多了，或者 nextPageStart 为空，直接跳出
                if (!hasMore || pageStart == null || pageStart.isEmpty() || !pageTokens.add(pageStart) || RequestManager.isVerificationPaused() || ApplicationHook.offline) {
                    break;
                }

                // 稍微控制一下频率
                GlobalThreadPools.sleepCompat(300);
            }
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "获取全量图鉴异常", th);
        }
        return allBooks;
    }





    /**
     * 自动合成图鉴
     */
    private void autoGenerateBook() {
        try {
            // 1. 直接获取所有页合并后的完整图鉴数组
            JSONArray allBooks = getAllBookList(this::active);

            if (allBooks.length() == 0) {
                return;
            }

            // 2. 遍历全量数组
            for (int i = 0; i < allBooks.length() && active(); i++) {
                JSONObject bookItem = allBooks.getJSONObject(i);

                // 判断是否可以合成勋章
                if (!"CAN_GENERATE".equals(bookItem.optString("medalGenerationStatus"))) {
                    continue;
                }

                JSONObject animalBookResult = bookItem.optJSONObject("animalBookResult");
                if (animalBookResult == null) {
                    Log.record(TAG,"animalBookResult为空，停止合成");
                    continue;

                }

                String bookId = animalBookResult.optString("bookId");
                String ecosystem = animalBookResult.optString("ecosystem");

                // 3. 调用合成接口
                String res = AntDodoRpcCall.generateBookMedal(bookId);
                JSONObject genResp = new JSONObject(res);

                if (ResChecker.checkRes(TAG, genResp)) {
                    Log.forest("神奇物种🦕合成勋章[" + ecosystem + "]");
                } else {
                    Log.record(TAG, "合成勋章失败[" + ecosystem + "]: " + genResp.optString("resultDesc"));
                }

                // 合成操作建议稍微加一点点延迟，保护接口
                GlobalThreadPools.sleepCompat(300);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "autoGenerateBook err:", t);
        }
    }

    public interface CollectToFriendType {
        int COLLECT = 0;
        int DONT_COLLECT = 1;
        String[] nickNames = {"选中帮抽卡", "选中不帮抽卡"};
    }


    //万能卡使用方法
    public interface UniversalCardUseType {

        /** 所有图鉴都可使用 */
        int ALL_COLLECTION = 0;

        /** 排除当前图鉴 */
        int EXCLUDE_CURRENT = 1;

        /** 优先合成进度最高的图鉴 */
        int PRIORITY_MAX_PROGRESS = 2;

        String[] nickNames = {
                "所有图鉴",
                "除当前图鉴",
                "优先合成进度最高"
        };
    }


}
