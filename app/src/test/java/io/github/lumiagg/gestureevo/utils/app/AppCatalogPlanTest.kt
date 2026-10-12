package io.github.lumiagg.gestureevo.utils.app

import org.junit.Assert.*
import org.junit.Test

class AppCatalogPlanTest {
    @Test fun unchangedCatalogProducesNoChanges() {
        val plan = planAppCatalogSync(setOf("mail", "browser"), listOf(
            CatalogPackage("mail", false), CatalogPackage("browser", false), CatalogPackage("removed", true),
        ))
        assertEquals(AppCatalogPlan(emptySet(), emptySet(), emptySet()), plan)
    }

    @Test fun addedAndRemovedPackagesKeepTheOriginalNormalSyncBehavior() {
        val plan = planAppCatalogSync(setOf("browser", "camera"), listOf(
            CatalogPackage("mail", false), CatalogPackage("browser", false),
        ))
        assertEquals(setOf("camera"), plan.added)
        assertEquals(setOf("mail"), plan.removed)
        assertTrue(plan.reactivated.isEmpty())
    }

    @Test fun reinstalledPackagesReactivateTheirExistingRecord() {
        val plan = planAppCatalogSync(setOf("mail"), listOf(CatalogPackage("mail", true)))
        assertEquals(setOf("mail"), plan.reactivated)
        assertTrue(plan.added.isEmpty())
        assertTrue(plan.removed.isEmpty())
    }

    @Test fun equalSetHashesCannotHidePackageReplacements() {
        assertEquals(setOf("Aa").hashCode(), setOf("BB").hashCode())
        val plan = planAppCatalogSync(setOf("BB"), listOf(CatalogPackage("Aa", false)))
        assertEquals(setOf("BB"), plan.added)
        assertEquals(setOf("Aa"), plan.removed)
    }

    @Test fun anEmptyInstalledSnapshotMarksActiveRecordsRemovedOnlyOnce() {
        val plan = planAppCatalogSync(emptySet(), listOf(CatalogPackage("mail", false), CatalogPackage("old", true)))
        assertEquals(setOf("mail"), plan.removed)
    }
}
