package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The five shipped vertical profiles load from data files, with no branch per profile in the loader itself
 * (CFG-001, SRS §3.4).
 */
class VerticalProfileCatalogTest {

    private final VerticalProfileCatalog catalog = new VerticalProfileCatalog(JsonMapper.builder().build());

    @Test
    void loadsExactlyTheFiveShippedProfiles() {
        assertThat(catalog.all()).hasSize(5).containsKeys(
                VerticalProfileId.BANKING, VerticalProfileId.HEALTHCARE, VerticalProfileId.PRODUCER_SERVICES, VerticalProfileId.GOVERNMENT, VerticalProfileId.GENERIC);
    }

    @Test
    void everyProfileCarriesLabelsCatalogueClassesNumberingReportsAndFlagsPerSrs33() {
        for (VerticalProfileId id : VerticalProfileId.values()) {
            VerticalProfileDefinition definition = catalog.get(id);
            assertThat(definition.id()).isEqualTo(id.wire());
            assertThat(definition.labels()).as(id + " labels").containsKeys(
                    "entity.visitor", "entity.visitor_id", "entity.service_group", "entity.counter", "entity.agent", "entity.category", "entity.ticket");
            for (var entry : definition.labels().entrySet()) {
                assertThat(entry.getValue()).as(id + " " + entry.getKey()).containsKeys("en", "bn");
            }
            assertThat(definition.starterServices()).as(id + " starter services").isNotEmpty();
            assertThat(definition.numberingDefaults()).as(id + " numbering defaults").isNotNull();
            assertThat(definition.reportDefaults()).as(id + " report defaults").isNotEmpty();
            assertThat(definition.kpiThresholds()).as(id + " kpi thresholds").isNotEmpty();
            assertThat(definition.featureFlags()).as(id + " feature flags").isNotEmpty();
        }
    }

    @Test
    void bankingAndHealthcareCarryDistinctTerminologyPerSrs32() {
        assertThat(catalog.get(VerticalProfileId.BANKING).labels().get("entity.visitor").get("en")).isEqualTo("Customer");
        assertThat(catalog.get(VerticalProfileId.HEALTHCARE).labels().get("entity.visitor").get("en")).isEqualTo("Patient");
        assertThat(catalog.get(VerticalProfileId.PRODUCER_SERVICES).labels().get("entity.visitor").get("en")).isEqualTo("Producer");
    }

    @Test
    void producerCategoryLabelIsABareNounNotTheSrsExample() {
        // The SRS §3.2 table's "(e.g. Children Tailoring)" illustrates a value. Screens compose "{Visitor} {category}"
        // ("Producer category"), so the term itself carries neither the example nor the entity's own name; in Bangla
        // it must not be বিভাগ either, which is already this profile's word for Department.
        assertThat(catalog.get(VerticalProfileId.PRODUCER_SERVICES).labels().get("entity.category"))
                .containsEntry("en", "Category")
                .containsEntry("bn", "শ্রেণি");
    }

    @Test
    void genericProfileTurnsEveryOptionalFlagOffPerSrs34() {
        assertThat(catalog.get(VerticalProfileId.GENERIC).featureFlags().values()).allMatch(enabled -> !enabled);
    }
}
