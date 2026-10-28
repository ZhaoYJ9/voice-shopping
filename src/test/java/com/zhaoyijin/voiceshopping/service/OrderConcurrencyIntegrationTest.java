package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.entity.OrderEntity;
import com.zhaoyijin.voiceshopping.entity.ProductEntity;
import com.zhaoyijin.voiceshopping.repository.OrderRepository;
import com.zhaoyijin.voiceshopping.repository.ProductRepository;
import com.zhaoyijin.voiceshopping.security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real PostgreSQL/Redis tests. Tables live in a fresh, private schema. */
@EnabledIfSystemProperty(named = "order.test.jdbc-url", matches = ".+")
class OrderConcurrencyIntegrationTest {
    private static final String SCHEMA = "order_test_" + UUID.randomUUID().toString().replace("-", "");
    private static PGSimpleDataSource dataSource;
    private static AnnotationConfigApplicationContext context;
    private static OrderService service;
    private static OrderRepository orders;
    private static ProductRepository products;
    private static PendingOrderStore pending;
    private static PurchaseEvents events;
    private static LongTermMemoryWriter writer;
    private static TransactionTemplate transactions;
    private final List<String> sessions = new ArrayList<>();

    @BeforeAll static void startContext() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("order.test.jdbc-url"));
        dataSource.setUser(System.getProperty("order.test.db-user", "order_test"));
        dataSource.setPassword(System.getProperty("order.test.db-password", ""));
        dataSource.setApplicationName(SCHEMA);
        new JdbcTemplate(dataSource).execute("CREATE SCHEMA " + SCHEMA);
        dataSource.setCurrentSchema(SCHEMA);
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(OrderService.class);
        orders = context.getBean(OrderRepository.class);
        products = context.getBean(ProductRepository.class);
        pending = context.getBean(PendingOrderStore.class);
        events = context.getBean(PurchaseEvents.class);
        writer = context.getBean(LongTermMemoryWriter.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll static void stopContext() {
        if (context != null) context.close();
        if (dataSource != null) new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @BeforeEach void resetData() {
        reset(pending, writer);
        orders.deleteAll();
        products.deleteAll();
        events.purchases.clear();
        events.fail = false;
    }

    @AfterEach void removeOnlyTestSessions() {
        reset(pending);
        sessions.forEach(pending::remove);
    }

    @Test void twoBuyersCannotBothBuyTheLastItem() throws Exception {
        ProductEntity product = product(1);
        String first = preview(product, 1L);
        String second = preview(product, 2L);

        List<Object> results = confirmTogether(product.getId(), first, second);

        assertEquals(1, results.stream().filter(OrderEntity.class::isInstance).count(), results.toString());
        assertEquals(1, results.stream().filter(IllegalStateException.class::isInstance).count());
        assertEquals(1, orders.count());
        assertEquals(0, stock(product));
        assertEquals(1, events.purchases.size());
    }

    @Test void duplicateConfirmationOnlyCreatesAndChargesOneOrder() throws Exception {
        ProductEntity product = product(1);
        String session = preview(product, 1L);

        List<Object> results = confirmTogether(product.getId(), session, session);

        assertInstanceOf(OrderEntity.class, results.get(0));
        assertInstanceOf(OrderEntity.class, results.get(1));
        assertEquals(((OrderEntity) results.get(0)).getId(), ((OrderEntity) results.get(1)).getId());
        assertEquals(1, orders.count());
        assertEquals(0, stock(product));
        assertEquals(1, events.purchases.size());
        verify(writer, times(1)).flushOnSessionEnd(session, 1L);
    }

    @Test void rollbackKeepsPreviewAndDoesNotPublishPurchase() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);

        transactions.executeWithoutResult(tx -> {
            service.confirm(session);
            tx.setRollbackOnly();
        });

        assertEquals(0, orders.count());
        assertEquals(3, stock(product));
        assertNotNull(pending.get(session), "A failed transaction must leave the preview available for retry");
        assertTrue(events.purchases.isEmpty());
        verifyNoInteractions(writer);
        service.confirm(session);
        assertEquals(1, orders.count());
        assertEquals(2, stock(product));
    }

    @Test void committingOldOrderPreservesNewPreviewInSameSession() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);
        String firstOrderNo = pending.get(session).orderNo();

        PendingOrderStore.PendingOrder next = transactions.execute(tx -> {
            service.confirm(session);
            return service.preview(session, 1L, product.getId(), 1);
        });

        assertNotNull(next);
        assertNotEquals(firstOrderNo, next.orderNo());
        assertEquals(next, pending.get(session));
        assertEquals(next.orderNo(), service.confirm(session).getOrderNo());
        assertEquals(2, orders.count());
        assertEquals(1, stock(product));
        assertNull(pending.get(session));
    }

    @Test void cacheCleanupFailureCanBeRetriedWithoutChargingAgain() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test cleanup failure"))
                .doCallRealMethod().when(pending).removeIfMatches(eq(session), anyString());

        OrderEntity first = service.confirm(session);
        assertNotNull(pending.get(session));
        OrderEntity retry = service.confirm(session);

        assertEquals(first.getId(), retry.getId());
        assertEquals(1, orders.count());
        assertEquals(2, stock(product));
        assertNull(pending.get(session));
        assertEquals(1, events.purchases.size());
        verify(writer, times(1)).flushOnSessionEnd(session, 1L);
    }

    @Test void failedNotificationsDoNotTurnCommittedOrderIntoFailure() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);
        events.fail = true;
        doThrow(new RejectedExecutionException("test async failure"))
                .when(writer).flushOnSessionEnd(session, 1L);

        OrderEntity order = assertDoesNotThrow(() -> service.confirm(session));

        assertTrue(orders.findById(order.getId()).isPresent());
        assertEquals(2, stock(product));
        assertNull(pending.get(session));
        verify(writer).flushOnSessionEnd(session, 1L);
    }

    @Test void legacyPreviewMustBeRecreatedBeforeConfirmation() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);
        var redis = context.getBean(StringRedisTemplate.class);
        String key = "vs:pending_order:" + session;
        // Emulate an actual pre-upgrade JSON record, with no orderNo property.
        Map<?, ?> old = new ObjectMapper().readValue(redis.opsForValue().get(key), Map.class);
        old.remove("orderNo");
        redis.opsForValue().set(key, new ObjectMapper().writeValueAsString(old), Duration.ofMinutes(10));

        assertThrows(IllegalStateException.class, () -> service.confirm(session));
        assertEquals(0, orders.count());
        assertEquals(3, stock(product));
        assertTrue(events.purchases.isEmpty());

        service.preview(session, 1L, product.getId(), 1);
        service.confirm(session);
        assertEquals(1, orders.count());
    }

    @Test void nonPositiveQuantityCannotIncreaseStockOrCreateAnOrder() {
        ProductEntity product = product(3);
        String session = preview(product, 1L);
        for (int qty : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> service.preview(session, 1L, product.getId(), qty));
            pending.put(new PendingOrderStore.PendingOrder(session, 1L, 1L, product.getId(), product.getName(),
                    product.getSkuCode(), qty, product.getPrice(), BigDecimal.ZERO, UUID.randomUUID().toString()));
            assertThrows(IllegalArgumentException.class, () -> service.confirm(session));
        }
        assertEquals(0, orders.count());
        assertEquals(3, stock(product));
        assertTrue(events.purchases.isEmpty());
    }

    private List<Object> confirmTogether(Long productId, String first, String second) throws Exception {
        CyclicBarrier previewsRead = new CyclicBarrier(2);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            previewsRead.await(10, TimeUnit.SECONDS);
            return snapshot;
        }).when(pending).get(anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, productId);
                lock.executeQuery().close();
            }
            Future<Object> a = pool.submit(() -> confirmOrFailure(first));
            Future<Object> b = pool.submit(() -> confirmOrFailure(second));
            try {
                // Hold the row until both real transactions are waiting, rather than relying on sleeps.
                await().atMost(Duration.ofSeconds(10)).until(() -> new JdbcTemplate(dataSource)
                        .queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'",
                                Long.class, SCHEMA) >= 2);
            } finally {
                blocker.rollback();
            }
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            reset(pending);
        }
    }

    private Object confirmOrFailure(String session) {
        try { return service.confirm(session); }
        catch (RuntimeException error) { return error; }
    }

    private ProductEntity product(int stock) {
        ProductEntity p = new ProductEntity();
        p.setMerchantId(1L); p.setSkuCode(UUID.randomUUID().toString());
        p.setName("并发测试商品"); p.setCategoryL1("运动"); p.setCategoryL2("跑鞋");
        p.setPrice(BigDecimal.TEN); p.setStock(stock); p.setStatus("ON_SALE");
        p.setAttributes(Map.of());
        return products.saveAndFlush(p);
    }

    private String preview(ProductEntity product, Long userId) {
        String session = UUID.randomUUID().toString();
        sessions.add(session);
        service.preview(session, userId, product.getId(), 1);
        return session;
    }

    private int stock(ProductEntity product) { return products.findById(product.getId()).orElseThrow().getStock(); }

    static class PurchaseEvents {
        final List<UserBehaviorSink.ProductPurchasedEvent> purchases = new CopyOnWriteArrayList<>();
        boolean fail;
        @EventListener public void purchased(UserBehaviorSink.ProductPurchasedEvent event) {
            purchases.add(event);
            if (fail) throw new IllegalStateException("test listener failure");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ProductRepository.class)
    static class TestConfig {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan(ProductEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("order.test.redis-port")));
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
        @Bean PendingOrderStore pending(StringRedisTemplate redis) { return spy(new PendingOrderStore(redis, new ObjectMapper())); }
        @Bean LongTermMemoryWriter writer() { return mock(LongTermMemoryWriter.class); }
        @Bean PurchaseEvents purchaseEvents() { return new PurchaseEvents(); }
        @Bean OrderService orderService(OrderRepository orders, ProductRepository products, PendingOrderStore pending,
                                        LongTermMemoryWriter writer, ApplicationEventPublisher publisher) {
            return new OrderService(orders, mock(CurrentUser.class), products, pending, writer, publisher);
        }
    }
}
