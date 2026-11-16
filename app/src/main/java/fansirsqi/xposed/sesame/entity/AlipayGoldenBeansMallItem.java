package fansirsqi.xposed.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import fansirsqi.xposed.sesame.util.maps.IdMapManager;
import fansirsqi.xposed.sesame.util.maps.GoldenBeansMallItemMap;
import fansirsqi.xposed.sesame.util.maps.UserMap;

/** 配置页动态获取当前账号候选，不缓存其它账号的列表。 */
public class AlipayGoldenBeansMallItem extends MapperEntity {
    public AlipayGoldenBeansMallItem(String id, String name) { this.id = id; this.name = name; }
    public static List<AlipayGoldenBeansMallItem> getList() {
        GoldenBeansMallItemMap map = IdMapManager.getInstance(GoldenBeansMallItemMap.class);
        String uid = UserMap.INSTANCE.getCurrentUid();
        if (uid == null || uid.isEmpty()) return new ArrayList<>();
        map.load(uid);
        List<AlipayGoldenBeansMallItem> result = new ArrayList<>();
        for (Map.Entry<String, String> item : map.getMap().entrySet()) result.add(new AlipayGoldenBeansMallItem(item.getKey(), item.getValue()));
        return result;
    }
    public static void clear() { }
}
