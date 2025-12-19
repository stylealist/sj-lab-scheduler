package com.example.scheduler.util;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DataTypeUtil {
    private DataTypeUtil() {}

    /* ========= Map helpers ========= */

    /** 주어진 키들 중 먼저 발견되는 값 반환 (정확 매칭만) */
    public static Object first(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    /** 대소문자 무시 탐색 포함 (정확 매칭 → 대소문자 무시 순) */
    public static Object any(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        if (v != null) return v;
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                String mk = e.getKey();
                if (mk != null && mk.equalsIgnoreCase(k) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /* ========= String ========= */

    public static String s(Object o) {
        if (o == null) return null;
        String v = (o instanceof String) ? (String) o : String.valueOf(o);
        return v == null ? null : v.trim();
    }

    public static boolean isBlank(String str) { return str == null || str.trim().isEmpty(); }

    /* ========= Numbers ========= */

    public static Integer i(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        try { return Integer.valueOf(v); } catch (Exception e) { return null; }
    }

    public static Long l(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        try { return Long.valueOf(v); } catch (Exception e) { return null; }
    }

    public static Double d(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        try { return Double.valueOf(v); } catch (Exception e) { return null; }
    }

    public static BigDecimal decimal(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        try { return new BigDecimal(v); } catch (Exception e) { return null; }
    }

    /* ========= Date/Time ========= */

    /** LocalDate: ISO, yyyyMMdd, yyyy/MM/dd */
    public static LocalDate toLocalDate(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        DateTimeFormatter[] fs = {
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyyMMdd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd")
        };
        for (DateTimeFormatter f : fs) {
            try { return LocalDate.parse(v, f); } catch (DateTimeParseException ignore) {}
        }
        return null;
    }

    /** LocalDateTime: ISO, yyyy-MM-dd HH:mm:ss, yyyyMMddHHmmss, yyyy/MM/dd HH:mm:ss, Offset/Zoned */
    public static LocalDateTime toLocalDateTime(Object o) {
        String v = s(o); if (isBlank(v)) return null;
        DateTimeFormatter[] fs = {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        };
        for (DateTimeFormatter f : fs) {
            try { return LocalDateTime.parse(v, f); } catch (DateTimeParseException ignore) {}
        }
        try { return OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (DateTimeParseException ignore) {}
        try { return ZonedDateTime.parse(v).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (DateTimeParseException ignore) {}
        return null;
    }

    /** "540"→"0540", 2315→"2315", "05:40"→"0540" */
    public static String toHHmm(Object o) {
        String t = s(o); if (isBlank(t)) return null;
        t = t.replaceAll("\\D", "");
        if (t.isEmpty()) return null;
        if (t.length() > 4) t = t.substring(0, 4);
        return String.format("%4s", t).replace(' ', '0');
    }

    /* ========= 컬렉션 유틸 ========= */

    /** 리스트를 고정 크기 청크로 나눔 */
    public static <T> List<List<T>> chunk(List<T> list, int size) {
        if (list == null || list.isEmpty() || size <= 0) return List.of();
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }

    /** 키 추출 함수로 중복 제거(처음 등장한 요소만 유지) */
    public static <T, K> List<T> dedupeBy(List<T> list, Function<T, K> keyFn) {
        if (list == null || list.isEmpty()) return List.of();
        Map<K,T> map = new LinkedHashMap<>();
        for (T t : list) {
            if (t == null) continue;
            K k = keyFn.apply(t);
            if (k != null) map.putIfAbsent(k, t);
        }
        return new ArrayList<>(map.values());
    }

    /** null 아닌 것만 필터링 */
    public static <T> List<T> nonNulls(List<T> list) {
        if (list == null) return List.of();
        return list.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }
    public static Integer toInt(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:Integer.valueOf(s);}catch(Exception e){return null;}}
    public static Long toLong(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:Long.valueOf(s);}catch(Exception e){return null;}}
    public static BigDecimal toDecimal(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:new BigDecimal(s);}catch(Exception e){return null;}}

}
