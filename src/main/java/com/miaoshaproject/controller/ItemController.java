package com.miaoshaproject.controller;

import com.miaoshaproject.controller.viewobject.ItemVO;
import com.miaoshaproject.dataobject.ItemDO;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.CacheService;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.model.ItemModel;
import org.joda.time.format.DateTimeFormat;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Controller("/item")
@RequestMapping("/item")
@CrossOrigin(allowCredentials = "true", allowedHeaders = "*", originPatterns ="*")
public class ItemController extends BaseController {

    @Autowired
    private ItemService itemService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private PromoService promoService;

    // 创建商品的controller
    @RequestMapping(value = "/create", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createItem(@RequestParam(name = "title") String title,
                                       @RequestParam(name = "description") String description,
                                       @RequestParam(name = "price") BigDecimal price,
                                       @RequestParam(name = "stock") Integer stock,
                                       @RequestParam(name = "imgUrl") String imgUrl) throws BusinessException {
        //封装service请求用来创建商品
        ItemModel itemModel = new ItemModel();
        itemModel.setTitle(title);
        itemModel.setDescription(description);
        itemModel.setPrice(price);
        itemModel.setStock(stock);
        itemModel.setImgUrl(imgUrl);
        ItemModel itemModelForReturn = itemService.createItem(itemModel);
        ItemVO itemVO = convertVOFromItemModel(itemModelForReturn);
        return CommonReturnType.create(itemVO);   // itemVo需要实现get/set方法，否则response是fail
    }

    // 发布活动
    @RequestMapping(value = "/publishpromo", method={RequestMethod.GET})
    @ResponseBody
    public CommonReturnType publishpromo(@RequestParam(name = "id")Integer id) {
        promoService.publishPromo(id);
        return CommonReturnType.create(null);
    }

    // 商品详情页浏览
    @RequestMapping(value = "/get", method={RequestMethod.GET})
    @ResponseBody
    public CommonReturnType getItem(@RequestParam(name = "id")Integer id) {

        ItemModel itemModel = null;

        //先取本地缓存
        itemModel = (ItemModel) cacheService.getCommonCache("item_" + id);

        if(itemModel == null) {
            // 根据商品id到redis内获取
            itemModel = (ItemModel) redisTemplate.opsForValue().get("item_" + id);
            // 若redis内存在对应的itemModel，则访问下游service
            if(itemModel == null) {
                itemModel = itemService.getItemById(id);
                // 设置itemModel到redis内
                redisTemplate.opsForValue().set("item_"+id, itemModel);
                redisTemplate.expire("item_"+id,10, TimeUnit.MINUTES);
            }
            // 填充本地缓存
            cacheService.setCommonCache("item_" + id, itemModel);
        }

        ItemVO itemVO = convertVOFromItemModel(itemModel);
        return CommonReturnType.create(itemVO);
    }

    // 商品列表页面浏览
    @RequestMapping(value = "/list", method={RequestMethod.GET})
    @ResponseBody
    public CommonReturnType getItemList() {
        List<ItemModel> itemModels = itemService.listItem();
        List<ItemVO> itemVOS = new ArrayList<>();
        for (ItemModel itemModel : itemModels) {
            ItemVO itemVO = convertVOFromItemModel(itemModel);
            itemVOS.add(itemVO);
        }
        return CommonReturnType.create(itemVOS);
    }

    private ItemVO convertVOFromItemModel(ItemModel itemModel) {
        if(itemModel == null) {
            return null;
        }
        ItemVO itemVO = new ItemVO();
        BeanUtils.copyProperties(itemModel, itemVO);
        if(itemModel.getPromoModel() != null) {
            // 有正在进行或即将进行的秒杀活动
            itemVO.setPromoStatus(itemModel.getPromoModel().getStatus());
            itemVO.setPromoId(itemModel.getPromoModel().getId());
            itemVO.setPromoStartDate(itemModel.getPromoModel().getStartTime().toString(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")));
            itemVO.setPromoPrice(itemModel.getPromoModel().getPromoItemPrice());
        } else {
            itemVO.setPromoStatus(0);
        }
        return itemVO;
    }
}
