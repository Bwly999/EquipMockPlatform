package com.equipmock.agent.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValueConverter 单测（03 §5 全表正反例）：参数规范化串 + JSON→目标类型。
 */
class ValueConverterTest {

    // ------------------------------------------------------------------
    // 5.1 参数规范化串
    // ------------------------------------------------------------------

    @Test
    void normalizeNull() {
        assertEquals("null", ValueConverter.normalize(null));
    }

    @Test
    void normalizeBooleanCharNumberString() {
        assertEquals("true", ValueConverter.normalize(Boolean.TRUE));
        assertEquals("A", ValueConverter.normalize(Character.valueOf('A')));
        assertEquals("42", ValueConverter.normalize(Integer.valueOf(42)));
        assertEquals("3.5", ValueConverter.normalize(Double.valueOf(3.5)));
        assertEquals("hello", ValueConverter.normalize("hello"));
    }

    @Test
    void normalizeByteArrayToUppercaseHex() {
        assertEquals("A1B2", ValueConverter.normalize(new byte[]{(byte) 0xA1, (byte) 0xB2}));
        assertEquals("", ValueConverter.normalize(new byte[0]));
    }

    @Test
    void normalizeCharArrayToCodeUnitHex() {
        assertEquals("00410042", ValueConverter.normalize(new char[]{'A', 'B'}));
    }

    @Test
    void normalizeEnumByName() {
        assertEquals("SECOND", ValueConverter.normalize(SampleEnum.SECOND));
    }

    @Test
    void normalizeArrayAndListAndMap() {
        // 基本类型数组 → gson 数组
        assertEquals("[1,2,3]", ValueConverter.normalize(new int[]{1, 2, 3}));
        // List（内嵌 byte[] → $hex 标签，与配置侧编码一致）
        JsonArray tagged = new JsonArray();
        JsonObject hexTag = new JsonObject();
        hexTag.addProperty("$hex", "0102");
        tagged.add(hexTag);
        tagged.add(new JsonParser().parseString("\"x\""));
        assertEquals("[{\"$hex\":\"0102\"},\"x\"]",
                ValueConverter.normalize(Arrays.asList(new byte[]{1, 2}, "x")));
        // Map：key 排序保证稳定（b 在 a 前）
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put("b", 2);
        map.put("a", 1);
        assertEquals("{\"a\":1,\"b\":2}", ValueConverter.normalize(map));
    }

    @Test
    void normalizePojoSortedKeys() {
        // POJO → gson + key 排序（03 §5.1）
        SamplePojo pojo = new SamplePojo("n", 7, true);
        assertEquals("{\"flag\":true,\"name\":\"n\",\"num\":7}",
                ValueConverter.normalize(pojo));
        // 配置侧乱序 key → 同一规范串（深度相等的依据）
        JsonObject config = new JsonParser().parseString(
                "{\"num\":7,\"name\":\"n\",\"flag\":true}").getAsJsonObject();
        assertEquals(ValueConverter.normalize(pojo), ValueConverter.canonical(config));
    }

    @Test
    void normalizeUnknownObjectToIdentityString() {
        Object unknown = new Object();
        String out = ValueConverter.normalize(unknown);
        assertTrue(out.startsWith("java.lang.Object@"),
                "未知对象应为 类名@identityHash 形式: " + out);
        // 流（空字段对象）同样走 identity
        String stream = ValueConverter.normalize(System.in);
        assertTrue(stream.contains("@"), "流对象应为 identity 形式: " + stream);
        assertFalse(stream.startsWith("{"), "流对象不应序列化为 JSON: " + stream);
    }

    @Test
    void normalizeTruncatesAt4096() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append('x');
        }
        assertEquals(4096, ValueConverter.normalize(sb.toString()).length());
    }

    // ------------------------------------------------------------------
    // 5.2 JSON → 目标类型
    // ------------------------------------------------------------------

    private static JsonElement json(String s) {
        return new JsonParser().parseString(s);
    }

    @Test
    void fromJsonPrimitivesAndNarrowing() {
        // 窄化：5.0 → int 5
        assertEquals(Integer.valueOf(5), ValueConverter.fromJson(json("5.0"), int.class));
        assertEquals(Integer.valueOf(5), ValueConverter.fromJson(json("5"), Integer.class));
        assertEquals(Long.valueOf(9), ValueConverter.fromJson(json("9.2"), long.class));
        assertEquals(Byte.valueOf((byte) 1), ValueConverter.fromJson(json("1.7"), byte.class));
        assertEquals(Short.valueOf((short) 3), ValueConverter.fromJson(json("3"), short.class));
        assertEquals(Float.valueOf(1.5f), ValueConverter.fromJson(json("1.5"), float.class));
        assertEquals(Double.valueOf(2.25), ValueConverter.fromJson(json("2.25"), double.class));
        assertEquals(Boolean.TRUE, ValueConverter.fromJson(json("true"), boolean.class));
        assertEquals(Character.valueOf('c'), ValueConverter.fromJson(json("\"c\""), char.class));
        // null → 包装 null；null → 基本类型失败
        assertNull(ValueConverter.fromJson(json("null"), Integer.class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("null"), int.class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("\"x\""), int.class));
    }

    @Test
    void fromJsonStringAndEnum() {
        assertEquals("abc", ValueConverter.fromJson(json("\"abc\""), String.class));
        assertEquals("5", ValueConverter.fromJson(json("5"), String.class));
        assertEquals(SampleEnum.FIRST, ValueConverter.fromJson(json("\"FIRST\""), SampleEnum.class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("\"NOPE\""), SampleEnum.class));
    }

    @Test
    void fromJsonByteArrayHexAndB64() {
        assertArrayEquals(new byte[]{(byte) 0xA1, (byte) 0x02},
                (byte[]) ValueConverter.fromJson(json("{\"$hex\":\"A102\"}"), byte[].class));
        assertArrayEquals(new byte[]{1, 2, 3},
                (byte[]) ValueConverter.fromJson(
                        json("{\"$b64\":\"AQID\"}"), byte[].class)); // Base64(1,2,3)=AQID
        assertArrayEquals(new byte[]{9, 9},
                (byte[]) ValueConverter.fromJson(json("[9,9]"), byte[].class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{\"$hex\":\"ABC\"}"), byte[].class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{\"$b64\":\"!!\"}"), byte[].class));
    }

    @Test
    void fromJsonCharArray() {
        assertArrayEquals(new char[]{'A', 'B'},
                (char[]) ValueConverter.fromJson(json("{\"$hex\":\"00410042\"}"), char[].class));
        // $hex 对 byte[] 是按字节的：0041 → 两个字节
        assertArrayEquals(new byte[]{0x00, 0x41},
                (byte[]) ValueConverter.fromJson(json("{\"$hex\":\"0041\"}"), byte[].class));
        // char[] 的 $hex 必须按 4 位码元
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{\"$hex\":\"123\"}"), char[].class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{\"$b64\":\"AQ==\"}"), char[].class));
    }

    @Test
    void fromJsonArrays() {
        assertArrayEquals(new int[]{1, 2, 3},
                (int[]) ValueConverter.fromJson(json("[1,2,3]"), int[].class));
        assertArrayEquals(new String[]{"a", "b"},
                (String[]) ValueConverter.fromJson(json("[\"a\",\"b\"]"), String[].class));
        // 嵌套数组
        assertArrayEquals(new int[][]{{1}, {2, 3}},
                (int[][]) ValueConverter.fromJson(json("[[1],[2,3]]"), int[][].class));
        // null 元素进基本类型数组失败
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("[1,null]"), int[].class));
    }

    @Test
    void fromJsonListAndMap() throws Exception {
        // List<Integer>：元素类型来自泛型签名
        Type listOfInteger = SampleTypes.class.getDeclaredField("listOfInteger").getGenericType();
        List<?> list = (List<?>) ValueConverter.fromJson(json("[1,2]"), listOfInteger);
        assertEquals(Arrays.asList(1, 2), new ArrayList<Object>(list));

        // Map<String,Long>
        Type mapType = SampleTypes.class.getDeclaredField("mapStringLong").getGenericType();
        @SuppressWarnings("unchecked")
        Map<String, Long> map = (Map<String, Long>) ValueConverter.fromJson(
                json("{\"a\":1,\"b\":2}"), mapType);
        assertEquals(Long.valueOf(1), map.get("a"));
        assertEquals(Long.valueOf(2), map.get("b"));

        // 原生 List（无泛型）：默认映射
        List<?> raw = (List<?>) ValueConverter.fromJson(json("[1,\"s\"]"), List.class);
        assertEquals(2, raw.size());
    }

    @Test
    void fromJsonPojoNoArgCtorFieldInjection() {
        // 公有无参构造 + 按字段注入（递归：嵌套 POJO）
        String nested = "{\"name\":\"outer\",\"num\":3,\"flag\":true,"
                + "\"inner\":{\"name\":\"in\",\"num\":1,\"flag\":false}}";
        OuterPojo outer = (OuterPojo) ValueConverter.fromJson(json(nested), OuterPojo.class);
        assertEquals("outer", outer.name);
        assertEquals(3, outer.num);
        assertTrue(outer.flag);
        assertEquals("in", outer.inner.name);
        assertFalse(outer.inner.flag);
        // 未知字段宽松跳过
        OuterPojo withUnknown = (OuterPojo) ValueConverter.fromJson(
                json("{\"name\":\"x\",\"num\":0,\"flag\":false,\"zzz\":1}"), OuterPojo.class);
        assertEquals("x", withUnknown.name);
    }

    @Test
    void fromJsonPojoAllArgsCtorFallback() {
        // 无无参构造（final 字段全参构造，按成员出现顺序注入）——DeviceStatus 形态
        AllArgsPojo pojo = (AllArgsPojo) ValueConverter.fromJson(
                json("{\"powered\":true,\"voltage\":220,\"current\":11}"), AllArgsPojo.class);
        assertEquals("AllArgsPojo{powered=true, voltage=220, current=11}", pojo.toString());
    }

    @Test
    void fromJsonUnsupportedTypeFails() {
        // 接口/抽象（List/Map 之外的）不支持 → 转换失败 → 调用方放行 REAL
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{}"), CharSequence.class));
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{}"),
                        java.io.InputStream.class));
        // 无公共构造的 POJO
        assertThrows(ValueConverter.ConversionException.class,
                () -> ValueConverter.fromJson(json("{}"), NoCtorPojo.class));
    }

    @Test
    void fromJsonNullToObjectTypes() {
        assertNull(ValueConverter.fromJson(json("null"), String.class));
        assertNull(ValueConverter.fromJson(json("null"), byte[].class));
        assertNull(ValueConverter.fromJson(json("null"), SamplePojo.class));
    }

    // ------------------------------------------------------------------
    // 测试载体
    // ------------------------------------------------------------------

    enum SampleEnum {FIRST, SECOND}

    /** 普通可变 POJO（公有无参构造 + 公有字段） */
    public static class SamplePojo {
        public String name;
        public int num;
        public boolean flag;

        public SamplePojo() {
        }

        SamplePojo(String name, int num, boolean flag) {
            this.name = name;
            this.num = num;
            this.flag = flag;
        }
    }

    public static class OuterPojo {
        public String name;
        public int num;
        public boolean flag;
        public SamplePojo inner;
    }

    /** final 字段 + 全参构造（无无参构造）——DeviceStatus 同形态 */
    public static class AllArgsPojo {
        public final boolean powered;
        public final int voltage;
        public final int current;

        public AllArgsPojo(boolean powered, int voltage, int current) {
            this.powered = powered;
            this.voltage = voltage;
            this.current = current;
        }

        @Override
        public String toString() {
            return "AllArgsPojo{powered=" + powered + ", voltage=" + voltage
                    + ", current=" + current + "}";
        }
    }

    /** 无公共构造 */
    static class NoCtorPojo {
        private NoCtorPojo() {
        }
    }

    @SuppressWarnings("unused")
    private static class SampleTypes {
        List<Integer> listOfInteger;
        Map<String, Long> mapStringLong;
    }
}
