package com.zhaoyijin.voiceshopping.service;

import com.zhaoyijin.voiceshopping.entity.OrderEntity;
import com.zhaoyijin.voiceshopping.entity.ProductEntity;
import com.zhaoyijin.voiceshopping.repository.OrderRepository;
import com.zhaoyijin.voiceshopping.repository.ProductRepository;
import com.zhaoyijin.voiceshopping.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepo;
    private final CurrentUser currentUser;
    private final ProductRepository productRepo;
    private final PendingOrderStore pendingStore;
    private final LongTermMemoryWriter longTermMemoryWriter;
    private final ApplicationEventPublisher eventPublisher;


    /**
     * 我的某一笔订单详情；查不到或不是自己的，一律 404 不泄露存在性
     */
    public OrderEntity getForUser(Long orderId) {
        return orderRepo.findByIdAndUserId(orderId, currentUser.id())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "订单不存在或无权访问"));
    }

    /**
     * 我的所有订单（按时间倒序）
     */
    public List<OrderEntity> listMine() {
        return orderRepo.findByUserIdOrderByCreatedAtDesc(currentUser.id());
    }

    /** 语音会话内查询刚才的订单，同时限定所属用户。 */
    public Optional<OrderEntity> latestForSession(String sessionId, Long userId) {
        return orderRepo.findFirstBySessionIdAndUserIdOrderByCreatedAtDesc(sessionId, userId);
    }

    /**
     * 商家后台查自家订单。本节先允许"传什么查什么"，
     * 真正的商家身份鉴权后续章节再补。
     */
    public List<OrderEntity> listForMerchant(Long merchantId) {
        return orderRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }


    /**
     * 生成十分钟有效的预览单；不预占库存，确认时在数据库事务内扣减。
     */
    public PendingOrderStore.PendingOrder preview(String sessionId, Long userId, Long productId, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("购买数量必须大于零");
        ProductEntity p = productRepo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        if (p.getStock() < qty) {
            throw new IllegalStateException("库存不足");
        }

        PendingOrderStore.PendingOrder po = new PendingOrderStore.PendingOrder(
                sessionId, userId, p.getMerchantId(), p.getId(), p.getName(),
                p.getSkuCode(), qty, p.getPrice(),
                p.getPrice().multiply(BigDecimal.valueOf(qty)),
                UUID.randomUUID().toString().replace("-", ""));
        pendingStore.put(po);
        return po;
    }

    /**
     * 用户确认后真正写单。
     */
    @Transactional
    public OrderEntity confirm(String sessionId) {
        PendingOrderStore.PendingOrder po = pendingStore.get(sessionId);
        if (po == null) throw new IllegalStateException("没有待确认订单，或已过期");
        // 升级前的缓存没有幂等标识，不能在每次重试时临时生成新的订单号。
        if (po.orderNo() == null || po.orderNo().isBlank()) {
            throw new IllegalStateException("预览已失效，请重新选择商品");
        }
        if (po.quantity() <= 0) throw new IllegalArgumentException("购买数量必须大于零");

        ProductEntity p = productRepo.findByIdForUpdate(po.productId())
                .orElseThrow(() -> new IllegalStateException("商品不存在"));
        // 持锁后再查订单：等待前一事务的重复确认可直接复用已提交的结果。
        Optional<OrderEntity> existing = orderRepo.findByOrderNo(po.orderNo());
        if (existing.isPresent()) {
            afterCommit(() -> clearConfirmedPreview(po));
            return existing.get();
        }
        if (p.getStock() < po.quantity()) throw new IllegalStateException("库存不足");
        p.setStock(p.getStock() - po.quantity());
        productRepo.save(p);

        OrderEntity o = new OrderEntity();
        o.setOrderNo(po.orderNo());
        o.setUserId(po.userId());
        o.setMerchantId(po.merchantId());
        o.setSessionId(sessionId);
        o.setProductId(po.productId());
        o.setSkuCode(po.skuCode());
        o.setQuantity(po.quantity());
        o.setUnitPrice(po.unitPrice());
        o.setTotalAmount(po.totalAmount());
        o.setStatus("PAID");
        o.setAgentAttribution(true);
        OrderEntity saved = orderRepo.save(o);

        afterCommit(() -> {
            clearConfirmedPreview(po);
            // 画像通知失败不能把已提交订单伪装成失败，重复确认也不能重复发布购买事件。
            try {
                eventPublisher.publishEvent(new UserBehaviorSink.ProductPurchasedEvent(
                        saved.getUserId(), saved.getProductId(), p.getCategoryL2(),
                        saved.getTotalAmount().doubleValue()));
            } catch (RuntimeException e) {
                log.warn("订单已提交，购买事件发布失败 orderNo={}", po.orderNo(), e);
            }
            try {
                longTermMemoryWriter.flushOnSessionEnd(sessionId, po.userId());
            } catch (RuntimeException e) {
                log.warn("订单已提交，长期记忆回流触发失败 orderNo={}", po.orderNo(), e);
            }
        });

        return saved;
    }

    private void clearConfirmedPreview(PendingOrderStore.PendingOrder po) {
        try {
            pendingStore.removeIfMatches(po.sessionId(), po.orderNo());
        } catch (RuntimeException e) {
            // Redis 恢复后，固定订单号使残留预览的重试仍复用同一笔订单。
            log.warn("订单已提交，预览清理失败 orderNo={}", po.orderNo(), e);
        }
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    public void cancel(String sessionId) {
        pendingStore.remove(sessionId);
    }

}
