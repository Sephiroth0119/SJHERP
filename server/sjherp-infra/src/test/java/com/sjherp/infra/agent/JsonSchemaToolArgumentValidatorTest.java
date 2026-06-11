package com.sjherp.infra.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * JsonSchemaToolArgumentValidator 单元测试（M1-T03 完整参数校验：type / required / enum）。
 */
class JsonSchemaToolArgumentValidatorTest {

    private final JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "documentId":{"type":"string"},
              "qty":{"type":"integer"},
              "rate":{"type":"number"},
              "force":{"type":"boolean"},
              "warehouse":{"type":"string","enum":["WH-01","WH-02"]},
              "detail":{"type":"object"},
              "items":{"type":"array"}
            },"required":["documentId"]}""";

    @Test
    void passesValidArguments() {
        List<String> errors = validator.validate(SCHEMA, Map.of(
                "documentId", "DOC-1", "qty", 5, "rate", 1.5, "force", true,
                "warehouse", "WH-01", "detail", Map.of("a", 1), "items", List.of(1)));
        assertThat(errors).isEmpty();
    }

    @Test
    void reportsMissingRequired() {
        assertThat(validator.validate(SCHEMA, Map.of("qty", 1)))
                .singleElement().asString().contains("documentId");
    }

    @Test
    void reportsTypeMismatch() {
        List<String> errors = validator.validate(SCHEMA, Map.of(
                "documentId", 123, "qty", "five", "force", "yes"));
        assertThat(errors).hasSize(3);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("documentId").contains("string"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("qty").contains("integer"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("force").contains("boolean"));
    }

    @Test
    void integerAcceptsIntegralFloatRepresentation() {
        // 模型偶尔把整数输出成 3.0：按 integer 放行；3.5 拒绝
        assertThat(validator.validate(SCHEMA, Map.of("documentId", "D", "qty", 3.0))).isEmpty();
        assertThat(validator.validate(SCHEMA, Map.of("documentId", "D", "qty", 3.5)))
                .singleElement().asString().contains("qty");
    }

    @Test
    void reportsEnumViolation() {
        assertThat(validator.validate(SCHEMA, Map.of("documentId", "D", "warehouse", "WH-99")))
                .singleElement().asString().contains("WH-01");
    }

    @Test
    void rejectsExtraFieldsWhenAdditionalPropertiesFalse() {
        String schema = """
                {"type":"object","properties":{"a":{"type":"string"}},\
                "required":["a"],"additionalProperties":false}""";
        assertThat(validator.validate(schema, Map.of("a", "x", "b", "y")))
                .singleElement().asString().contains("b");
        // 未显式声明 additionalProperties=false 时不拦截多余字段
        assertThat(validator.validate(SCHEMA, Map.of("documentId", "D", "extra", "x"))).isEmpty();
    }

    @Test
    void nullOrBlankSchemaMeansNoConstraint() {
        assertThat(validator.validate(null, Map.of())).isEmpty();
        assertThat(validator.validate(" ", Map.of())).isEmpty();
    }

    @Test
    void invalidSchemaReportsSchemaErrorInsteadOfCrashing() {
        assertThat(validator.validate("{broken", Map.of()))
                .singleElement().asString().contains("schema");
    }
}
