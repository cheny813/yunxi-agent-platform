package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出产物清单（manifest.json）：记录定义 key、translator 版本、本地预校验结论与引用实体，
 * 供运维核对与版本漂移比对。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Manifest {

    /** aistio 定义 key（与 mount.body.definition 引用对应） */
    private String definitionKey;

    /** translator 版本 */
    private String translatorVersion;

    /** 生成时间（ISO-8601） */
    private String generatedAt;

    /** definition.json 的 SHA-256（hex），用于版本漂移比对 */
    private String specChecksum;

    /** 本地预校验结论 */
    private Precheck precheck = new Precheck();

    /** 图引用的实体清单（E3 校验对象） */
    private ReferencedEntities referencedEntities = new ReferencedEntities();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Precheck {
        private List<ValidationIssue> hardErrors = new ArrayList<>();
        private List<ValidationIssue> warnings = new ArrayList<>();

        public List<ValidationIssue> getHardErrors() {
            return hardErrors;
        }

        public void setHardErrors(List<ValidationIssue> hardErrors) {
            this.hardErrors = hardErrors;
        }

        public List<ValidationIssue> getWarnings() {
            return warnings;
        }

        public void setWarnings(List<ValidationIssue> warnings) {
            this.warnings = warnings;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReferencedEntities {
        private List<String> agentIds = new ArrayList<>();
        private List<String> teamRefs = new ArrayList<>();
        private List<String> definitionRevisionIds = new ArrayList<>();

        public List<String> getAgentIds() {
            return agentIds;
        }

        public void setAgentIds(List<String> agentIds) {
            this.agentIds = agentIds;
        }

        public List<String> getTeamRefs() {
            return teamRefs;
        }

        public void setTeamRefs(List<String> teamRefs) {
            this.teamRefs = teamRefs;
        }

        public List<String> getDefinitionRevisionIds() {
            return definitionRevisionIds;
        }

        public void setDefinitionRevisionIds(List<String> definitionRevisionIds) {
            this.definitionRevisionIds = definitionRevisionIds;
        }
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
    }

    public String getTranslatorVersion() {
        return translatorVersion;
    }

    public void setTranslatorVersion(String translatorVersion) {
        this.translatorVersion = translatorVersion;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getSpecChecksum() {
        return specChecksum;
    }

    public void setSpecChecksum(String specChecksum) {
        this.specChecksum = specChecksum;
    }

    public Precheck getPrecheck() {
        return precheck;
    }

    public void setPrecheck(Precheck precheck) {
        this.precheck = precheck;
    }

    public ReferencedEntities getReferencedEntities() {
        return referencedEntities;
    }

    public void setReferencedEntities(ReferencedEntities referencedEntities) {
        this.referencedEntities = referencedEntities;
    }
}
