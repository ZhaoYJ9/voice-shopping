package com.zhaoyijin.voiceshopping.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlFilterBuilderTest {
    @Test
    void explicitRangeFiltersBothBoundsInTheProductQuery() {
        SqlFilterBuilder.Filter filter = SqlFilterBuilder.fromSlots(
                Map.of("category", "跑鞋", "budgetMin", 1000, "budget", 1500));
        assertEquals("category_l2 = ? AND price <= ? AND price >= ?", filter.clause());
        assertEquals(List.of("跑鞋", 1500.0, 1000.0), filter.params());
    }

    @Test
    void sportsShoesIncludeShoeSubcategoriesWithoutLosingBudget() {
        SqlFilterBuilder.Filter filter = SqlFilterBuilder.fromSlots(
                Map.of("category", "运动鞋", "budget", 1000));

        assertEquals("(category_l2 = ? OR (category_l1 = ? AND category_l2 LIKE ?)) AND price <= ?",
                filter.clause());
        assertEquals(List.of("运动鞋", "运动", "%鞋", 1000.0), filter.params());
    }

    @Test
    void genericShoesKeepBudgetAndDoNotIncludeOtherProductTypes() {
        SqlFilterBuilder.Filter filter = SqlFilterBuilder.fromSlots(
                Map.of("category", "鞋", "budget", 500));

        assertEquals("category_l2 LIKE ? AND price <= ?", filter.clause());
        assertEquals(List.of("%鞋", 500.0), filter.params());
    }

    @Test
    void specificCategoryStillRequiresExactMatch() {
        SqlFilterBuilder.Filter filter = SqlFilterBuilder.fromSlots(
                Map.of("category", "跑鞋", "budget", 1000, "brand", "Nike"));

        assertEquals("category_l2 = ? AND price <= ? AND brand = ?", filter.clause());
        assertEquals(List.of("跑鞋", 1000.0, "Nike"), filter.params());
    }
}
