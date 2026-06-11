package com.sjherp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * RequiredFieldsToolArgumentValidator 单元测试（M1-T03 零依赖朴素实现）。
 */
class RequiredFieldsToolArgumentValidatorTest {

    private final RequiredFieldsToolArgumentValidator validator = new RequiredFieldsToolArgumentValidator();

    private static final String SCHEMA = """
            {"type":"object","properties":{"message":{"type":"string"},"count":{"type":"integer"}},\
            "required":["message","count"]}""";

    @Test
    void passesWhenAllRequiredFieldsPresent() {
        List<String> errors = validator.validate(SCHEMA, Map.of("message", "hi", "count", 3));
        assertTrue(errors.isEmpty());
    }

    @Test
    void reportsEachMissingRequiredField() {
        List<String> errors = validator.validate(SCHEMA, Map.of("message", "hi"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("count"));
    }

    @Test
    void missingAllFieldsReportsAll() {
        List<String> errors = validator.validate(SCHEMA, Map.of());
        assertEquals(2, errors.size());
    }

    @Test
    void nullOrBlankSchemaMeansNoConstraint() {
        assertTrue(validator.validate(null, Map.of()).isEmpty());
        assertTrue(validator.validate("  ", Map.of()).isEmpty());
    }

    @Test
    void schemaWithoutRequiredMeansNoConstraint() {
        assertTrue(validator.validate("{\"type\":\"object\",\"properties\":{}}", Map.of()).isEmpty());
    }

    @Test
    void nullArgumentsTreatedAsEmpty() {
        List<String> errors = validator.validate(SCHEMA, null);
        assertEquals(2, errors.size());
    }

    @Test
    void extractsEscapedFieldNames() {
        List<String> names = RequiredFieldsToolArgumentValidator.extractRequiredNames(
                "{\"required\":[\"a\",\"b\"]}");
        assertEquals(List.of("a", "b"), names);
    }
}
