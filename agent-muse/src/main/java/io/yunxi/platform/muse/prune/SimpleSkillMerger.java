package io.yunxi.platform.muse.prune;

import io.yunxi.platform.muse.config.EvolutionConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
/**
 * 技能相似度计算器（中文友好）。
 *
 * <p>中文分词采用「CJK 二元字切分 + ASCII 词切分」的自包含方案（不引入外部分词依赖，
 * 保证在受限构件仓库下也可直接编译运行）。在此切分基础上按标准 TF-IDF
 * （idf = log(N/(df+1)) + 1）生成向量，再用余弦相似度衡量两个技能正文的重合度，
 * 供 {@link DefaultSkillPruner} 判定重复技能。</p>
 *
 * <p>注：若构件仓库可用，可无缝替换为 {@code com.huaban:jieba-analysis} 做更准的中文分词、
 * 以及 {@code com.github.haifengl:smile-math} 的 {@code Math} 做向量运算——本类的
 * {@link #tokenize(String)} 与余弦计算已隔离，替换点集中、对上层零影响。</p>
 */
@Slf4j
public class SimpleSkillMerger {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");

    /** 两个技能正文的余弦相似度（0~1）。 */
    public double similarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
        List<String> ta = tokenize(a);
        List<String> tb = tokenize(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;

        // 以 {a, b} 为小语料计算 idf
        Map<String, Integer> df = new LinkedHashMap<>();
        countDf(ta, df);
        countDf(tb, df);
        int n = 2;
        Map<String, Double> idf = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((double) n / (e.getValue() + 1)) + 1.0);
        }

        Map<String, Double> va = tfidf(ta, idf);
        Map<String, Double> vb = tfidf(tb, idf);

        Set<String> terms = new HashSet<>(va.keySet());
        terms.addAll(vb.keySet());
        List<String> dim = new ArrayList<>(terms);
        double[] xa = toArray(dim, va);
        double[] xb = toArray(dim, vb);
        double dot = dot(xa, xb);
        double na = norm2(xa);
        double nb = norm2(xb);
        if (na == 0 || nb == 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, dot / (na * nb)));
    }

    private void countDf(List<String> tokens, Map<String, Integer> df) {
        Set<String> seen = new HashSet<>(tokens);
        for (String t : seen) df.merge(t, 1, Integer::sum);
    }

    private Map<String, Double> tfidf(List<String> tokens, Map<String, Double> idf) {
        Map<String, Double> tf = new LinkedHashMap<>();
        int total = tokens.size();
        for (String t : tokens) tf.merge(t, 1.0, Double::sum);
        Map<String, Double> vec = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            double idfV = idf.getOrDefault(e.getKey(), 1.0);
            vec.put(e.getKey(), (e.getValue() / total) * idfV);
        }
        return vec;
    }

    private double[] toArray(List<String> dim, Map<String, Double> vec) {
        double[] arr = new double[dim.size()];
        for (int i = 0; i < dim.size(); i++) arr[i] = vec.getOrDefault(dim.get(i), 0.0);
        return arr;
    }

    private double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private double norm2(double[] a) {
        double s = 0;
        for (double v : a) s += v * v;
        return Math.sqrt(s);
    }

    /** 中文按连续 CJK 字符做二元切分；ASCII/数字按词切分并转小写。 */
    private List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = ASCII_WORD.matcher(text.toLowerCase());
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 2) out.add(w);
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CJK.matcher(String.valueOf(c)).matches()) {
                buf.append(c);
            } else {
                flushBigrams(out, buf);
                buf.setLength(0);
            }
        }
        flushBigrams(out, buf);
        return out;
    }

    private void flushBigrams(List<String> out, StringBuilder buf) {
        if (buf.length() < 2) {
            if (buf.length() == 1) out.add("c_" + buf.charAt(0));
            return;
        }
        for (int i = 0; i < buf.length() - 1; i++) {
            out.add("c_" + buf.charAt(i) + buf.charAt(i + 1));
        }
    }
}
