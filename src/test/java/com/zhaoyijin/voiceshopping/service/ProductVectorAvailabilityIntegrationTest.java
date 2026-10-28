package com.zhaoyijin.voiceshopping.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires PostgreSQL with pgvector installed in public; only the model call is mocked. */
@EnabledIfSystemProperty(named = "recommendation.test.jdbc-url", matches = ".+")
class ProductVectorAvailabilityIntegrationTest {
    private static final String SCHEMA = "recommend_test_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate jdbc;
    private ProductVectorService service;

    @BeforeAll static void createPrivateSchema() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(System.getProperty("recommendation.test.jdbc-url"));
        ds.setUser(System.getProperty("recommendation.test.db-user", "postgres"));
        ds.setPassword(System.getProperty("recommendation.test.db-password", ""));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        ds.setCurrentSchema(SCHEMA + ",public");
        jdbc.execute("""
                CREATE TABLE product (
                    id BIGINT PRIMARY KEY, stock INTEGER, status TEXT NOT NULL,
                    price NUMERIC NOT NULL, merchant_id BIGINT NOT NULL, category_l2 TEXT NOT NULL,
                    embedding vector(3)
                )
                """);
    }

    @AfterAll static void dropPrivateSchema() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @BeforeEach void setUp() {
        jdbc.execute("DELETE FROM product");
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embed("跑鞋")).thenReturn(new float[]{1, 0, 0});
        service = new ProductVectorService(jdbc, embeddings, new ProductTextBuilder());
    }

    @Test void filtersUnavailableProductsBeforeApplyingTopK() {
        insert(1, 0, "ON_SALE", "[1,0,0]", 80, 7, "跑鞋");
        insert(2, -1, "ON_SALE", "[1,0.05,0]", 80, 7, "跑鞋");
        insert(3, null, "ON_SALE", "[1,0.1,0]", 80, 7, "跑鞋");
        insert(4, 1, "ON_SALE", "[1,0.2,0]", 80, 7, "跑鞋");
        insert(5, 2, "ON_SALE", "[1,0.3,0]", 80, 7, "跑鞋");
        insert(6, 10, "OFF_SALE", "[1,0,0]", 80, 7, "跑鞋");
        insert(7, 10, "ON_SALE", null, 80, 7, "跑鞋");

        assertEquals(List.of(4L, 5L), service.search("跑鞋", null, List.of(), 2));
    }

    @Test void retainsBudgetMerchantAndCategoryFilters() {
        insert(1, 0, "ON_SALE", "[1,0,0]", 80, 7, "跑鞋");
        insert(2, 1, "ON_SALE", "[1,0.1,0]", 80, 7, "跑鞋");
        insert(3, 1, "ON_SALE", "[1,0.2,0]", 200, 7, "跑鞋");
        insert(4, 1, "ON_SALE", "[1,0.3,0]", 80, 8, "跑鞋");
        insert(5, 1, "ON_SALE", "[1,0.4,0]", 80, 7, "衣服");

        assertEquals(List.of(2L), service.search("跑鞋",
                "price <= ? AND merchant_id = ? AND category_l2 = ?", List.of(100, 7, "跑鞋"), 20));
    }

    @Test void returnsEmptyWhenNoStockIsAvailable() {
        insert(1, 0, "ON_SALE", "[1,0,0]", 80, 7, "跑鞋");
        assertTrue(service.search("跑鞋", "", List.of(), 20).isEmpty());
    }

    private void insert(long id, Integer stock, String status, String embedding, int price, long merchant, String category) {
        jdbc.update("INSERT INTO product VALUES (?, ?, ?, ?, ?, ?, ?::vector)",
                id, stock, status, price, merchant, category, embedding);
    }
}
