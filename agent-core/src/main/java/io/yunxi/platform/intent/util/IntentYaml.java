package io.yunxi.platform.intent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 意图引擎共享 YAML ObjectMapper。
 *
 * <p>消除各加载器（IntentTree / EntityDictionaryLoader / TerminologyProcessor /
 * IntentMappingTable）重复 {@code new ObjectMapper(new YAMLFactory())}。
 * ObjectMapper 与 YAMLFactory 配置后线程安全，可全局共享。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class IntentYaml {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private IntentYaml() {
    }

    /** 共享 YAML ObjectMapper（线程安全） */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
