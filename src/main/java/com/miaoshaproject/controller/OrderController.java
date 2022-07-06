package com.miaoshaproject.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.error.EmBussinessError;
import com.miaoshaproject.mq.MqProducer;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.OrderService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.model.OrderModel;
import com.miaoshaproject.service.model.UserModel;
import com.miaoshaproject.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.*;

@Controller("order")
@RequestMapping("/order")
@CrossOrigin(allowCredentials = "true", allowedHeaders = "*", originPatterns ="*")
public class OrderController extends BaseController {
    @Autowired
    private OrderService orderService;

    // 获取用户的request，来获取登录信息，前面将登录信息set到attribute中了
    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    // 限流
    private RateLimiter orderCreateRateLimiter;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(20);

        orderCreateRateLimiter = RateLimiter.create(300);
    }

    // 生成验证码
    @RequestMapping(value = "/generateVerifyCode", method={RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public void generateVerifyCode(HttpServletResponse response) throws BusinessException, IOException {
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能生成验证码");
        }
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel == null) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能生成验证码");
        }
        Map<String,Object> map = CodeUtil.generateCodeAndPic();
        redisTemplate.opsForValue().set("verify_code_" + userModel.getId(), map.get("code"));
        redisTemplate.expire("verify_code_" + userModel.getId(), 10, TimeUnit.MINUTES);
        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());

    }

    // 生成秒杀令牌 + 验证码技术
    @RequestMapping(value = "/generateToken", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generateToken(@RequestParam(name = "itemId")Integer itemid,
                                        @RequestParam(name = "promoId")Integer promoId,
                                          @RequestParam(name = "verifyCode")String verifyCode) throws BusinessException {
        // 根据token获取用户信息
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能下单");
        }
        // 获取用户的登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel == null) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能下单");
        }

        // 通过verifyCode验证验证码的有效性
        String verifyCodeInCache = (String) redisTemplate.opsForValue().get("verify_code_" + userModel.getId());
        if(StringUtils.isEmpty(verifyCodeInCache)) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR, "请求非法");
        }
        if(!verifyCodeInCache.equalsIgnoreCase(verifyCode)) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"请求非法，验证码错误");
        }


        // 获取秒杀访问令牌
        String promoToken = promoService.generateSecondKillToken(promoId, itemid, userModel.getId());
        if(promoToken == null) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"生成令牌失败");
        }
        // 返回对应结果
        return CommonReturnType.create(promoToken);
    }


        // 封装下单请求
    @RequestMapping(value = "/createorder", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name = "itemId")Integer itemid,
                                        @RequestParam(name = "amount")Integer amount,
                                        @RequestParam(name = "promoId", required = false)Integer promoId,
                                        @RequestParam(name = "promoToken", required = false)String promoToken) throws BusinessException {

//        Boolean is_login = (Boolean) httpServletRequest.getSession().getAttribute("IS_LOGIN");

        // 限流
        if(!orderCreateRateLimiter.tryAcquire()) {
            throw new BusinessException(EmBussinessError.RATELIMIT);
        }

        // 基于token传递sessionID
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能下单");
        }
        // 获取用户的登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel == null) {
            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能下单");
        }

        // 验证秒杀令牌是否正确
        if(promoId != null) {
            String inRedispromoToken = (String) redisTemplate.opsForValue().get("promo_token_" + promoId + "_userid_" + userModel.getId() + "_itemid_" + itemid);
            if(inRedispromoToken == null || !StringUtils.equals(inRedispromoToken,promoToken)) {
                throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌校验失败");
            }
        }

//        if(is_login == null || !is_login.booleanValue()) {
//            throw new BusinessException(EmBussinessError.USER_NOT_LOGIN,"用户还未登录，不能下单");
//        }

        // 获取用户的登录信息
//        UserModel login_user = (UserModel) httpServletRequest.getSession().getAttribute("LOGIN_USER");

//        OrderModel orderModel = orderService.createOrder(userModel.getId(), itemid, promoId, amount);

        // 判断库存是否已售罄，若对应的售罄key存在，则直接返回下单失败
        // 前置到秒杀令牌发放中
//        if(redisTemplate.hasKey("promo_item_stock_invalid_" + itemid)) {
//            throw new BusinessException(EmBussinessError.STOCK_NOT_ENOUGH);
//        }

        // 队列泄洪
        // 同步调用线程池的submit方法
        // 拥塞窗口为20的等待队列，用来队列化泄洪
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                // 先加入库存流水init状态
                String stockLogId = itemService.initStockLog(itemid, amount);

                // 再去完成对应的下单事务性消息机制。异步发送事务性消息
                if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemid, promoId, amount, stockLogId)) {
                    throw new BusinessException(EmBussinessError.UNKNOWN_ERROR, "下单失败");
                }
                return null;
            }
        });

        try {
            future.get();
        } catch (InterruptedException e) {
            throw new BusinessException(EmBussinessError.UNKNOWN_ERROR);
        } catch (ExecutionException e) {
            throw new BusinessException(EmBussinessError.UNKNOWN_ERROR);
        }

        // 队列泄洪
        // 先加入库存流水init状态
//        String stockLogId = itemService.initStockLog(itemid, amount);

        // 再去完成对应的下单事务性消息机制。异步发送事务性消息
//        if(!mqProducer.transactionAsyncReduceStock(userModel.getId(),itemid,promoId,amount,stockLogId)) {
//            throw new BusinessException(EmBussinessError.UNKNOWN_ERROR,"下单失败");
//        }
        return CommonReturnType.create(null);
    }
}
