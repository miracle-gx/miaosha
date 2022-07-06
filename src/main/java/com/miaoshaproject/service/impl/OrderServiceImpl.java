package com.miaoshaproject.service.impl;

import com.miaoshaproject.dao.OrderDOMapper;
import com.miaoshaproject.dao.SequenceDOMapper;
import com.miaoshaproject.dao.StockLogDOMapper;
import com.miaoshaproject.dataobject.OrderDO;
import com.miaoshaproject.dataobject.SequenceDO;
import com.miaoshaproject.dataobject.StockLogDO;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.error.EmBussinessError;
import com.miaoshaproject.mq.MqProducer;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.OrderService;
import com.miaoshaproject.service.UserService;
import com.miaoshaproject.service.model.ItemModel;
import com.miaoshaproject.service.model.OrderModel;
import com.miaoshaproject.service.model.UserModel;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ItemService itemService;
    @Autowired
    private UserService userService;
    @Autowired
    private OrderDOMapper orderDOMapper;
    @Autowired
    private SequenceDOMapper sequenceDOMapper;
    @Autowired
    private StockLogDOMapper stockLogDOMapper;


    @Override
    @Transactional
    public OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount,String stockLogId) throws BusinessException {
        // 1、校验下单状态，下单的商品是否存在，用户是否合法，购买数量是否正确
//        ItemModel itemById = itemService.getItemById(itemId);

        // 通过缓存获取商品
        ItemModel itemById = itemService.getItemByIdInCache(itemId);

        if(itemById == null) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR, "商品信息不存在");
        }

        // 以下逻辑全部放入生成秒杀令牌中完成
        // 通过缓存获取用户
//        UserModel userById = userService.getUserByIdInCache(userId);
//        if(userById == null) {
//            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR, "用户信息不存在");
//        }
        if(amount <= 0 || amount > 99) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR, "购买数量不合法");
        }

        // 校验活动信息
//        if(promoId != null) {
//            //(1)校验这个活动是否存在这个适用商品
//            if(promoId != itemById.getPromoModel().getId()) {
//                throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"活动信息不正确");
//            } else if(itemById.getPromoModel().getStatus() != 2) {
//                //(2)校验活动是否正在进行中
//                throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"活动还未开始");
//            }
//        }

        // 2、落单减库存
        boolean result = itemService.decreaseStock(itemId, amount);
        if(!result) {
            throw new BusinessException(EmBussinessError.STOCK_NOT_ENOUGH);
        }

        // 3、订单入库
        OrderModel orderModel = new OrderModel();
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        orderModel.setAmount(amount);
        if(promoId != null) {
            orderModel.setItemPrice(itemById.getPromoModel().getPromoItemPrice());
        } else {
            orderModel.setItemPrice(itemById.getPrice());
        }
        orderModel.setPromoId(promoId);
        orderModel.setOrderPrice(orderModel.getItemPrice().multiply(new BigDecimal(amount)));

        // 生成交易流水号(订单号)
        orderModel.setId(generateOrderNo());

        OrderDO orderDO = convertFromOrderModel(orderModel);
        orderDOMapper.insertSelective(orderDO);

        // 加上商品的销量
        itemService.increaseSales(itemId, amount);

        // 设置库存流水状态为成功
        StockLogDO stockLogDO = stockLogDOMapper.selectByPrimaryKey(stockLogId);
        if(stockLogDO == null) {
            throw new BusinessException(EmBussinessError.UNKNOWN_ERROR);
        }
        stockLogDO.setStatus(2);
        stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);

//        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//            @Override
//            public void afterCommit() {
//                // 异步更新库存
//                boolean mqResult = itemService.asyncDecreaseStock(itemId, amount);
////                if(!mqResult) {
////                    itemService.increaseStock(itemId, amount);
////                    throw new BusinessException(EmBussinessError.MQ_SEND_FAIL);
////                }
//            }
//        });

        // 4、返回前端
        return orderModel;
    }

    private OrderDO convertFromOrderModel(OrderModel orderModel) {
        if(orderModel == null) {
            return null;
        }
        OrderDO orderDO = new OrderDO();
        BeanUtils.copyProperties(orderModel, orderDO);
        // 将BigDecimal（Model）转化为double（数据库）
        orderDO.setItemPrice(orderModel.getItemPrice().doubleValue());
        orderDO.setOrderPrice(orderModel.getOrderPrice().doubleValue());
        return orderDO;
    }

    // 开启一个新事务，无论如何该新事务都会提交，保证自增序列不回滚，保证全局唯一
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateOrderNo() {
        // 订单号为16位
        StringBuffer stringBuffer = new StringBuffer();
        // 前8位位时间信息，年月日
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-","");
        stringBuffer.append(nowDate);

        // 中间6位位自增序列，通过sequence_info表实现
        // 获取当前sequence
        int sequence = 0;
        SequenceDO sequenceDO = sequenceDOMapper.getSequenceByName("order_info");
        sequence = sequenceDO.getCurrentValue();
        sequenceDO.setCurrentValue(sequenceDO.getCurrentValue() + sequenceDO.getStep());
        sequenceDOMapper.updateByPrimaryKeySelective(sequenceDO);

        String sequenceStr = String.valueOf(sequence);
        for(int i = 0; i + sequenceStr.length() < 6; i++) {
            stringBuffer.append("0");
        }
        stringBuffer.append(sequenceStr);

        // 最后两位位分库分表位（0-99） userId对100取余，暂时写死
        stringBuffer.append("00");
        return stringBuffer.toString();
    }
}
