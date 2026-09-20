package io.yunxi.platform.aistio.orchestration;

/**
 * 翻译结果：DefinitionSpec 模型、其 JSON 文本、以及导出清单。
 */
public class TranslationResult {

    private final DefinitionSpec spec;
    private final String definitionJson;
    private final Manifest manifest;

    public TranslationResult(DefinitionSpec spec, String definitionJson, Manifest manifest) {
        this.spec = spec;
        this.definitionJson = definitionJson;
        this.manifest = manifest;
    }

    public DefinitionSpec getSpec() {
        return spec;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public Manifest getManifest() {
        return manifest;
    }
}
