package com.miaoshaproject.controller;

import com.miaoshaproject.controller.viewobject.UserVO;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.error.EmBussinessError;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.UserService;
import com.miaoshaproject.service.model.UserModel;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.apache.tomcat.util.security.MD5Encoder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import sun.misc.BASE64Encoder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Controller("user")
@RequestMapping("/user")
@CrossOrigin(allowCredentials = "true", allowedHeaders = "*", originPatterns ="*")
public class UserController extends BaseController  {

    @Autowired
    private UserService userService;

    @Autowired
    private HttpServletRequest httpServletRequest; // threadlocal

    @Autowired
    RedisTemplate redisTemplate;

    // 用户登录接口
    @RequestMapping(value = "/login", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType login(@RequestParam(name="telphone")String telphone,
                                  @RequestParam(name="password")String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        // 入参检验
        if(StringUtils.isEmpty(telphone) || StringUtils.isEmpty(password)) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR);
        }
        //用户登录服务，用来校验用户登录是否合法
        UserModel userModel = userService.validateLogin(telphone, this.EncodeByMd5(password));

        // 基于cookie传输sessionID
        //将登录凭证加入到用户登录成功的session内
//        this.httpServletRequest.getSession().setAttribute("IS_LOGIN",true);
//        this.httpServletRequest.getSession().setAttribute("LOGIN_USER", userModel);

        // 基于token传输sessionId
        // 若用户登录验证成功后，将对应的登录信息和登录凭证一起存入redis中
        // 生成登录凭证token，UUID
        String uuidToken = UUID.randomUUID().toString();
        uuidToken = uuidToken.replace("-","");

        // 建立token和用户登录态之间的联系
        redisTemplate.opsForValue().set(uuidToken, userModel);
        redisTemplate.expire(uuidToken, 1, TimeUnit.HOURS);

        // 下发token
        return CommonReturnType.create(uuidToken);
    }

    // 用户注册接口
    @RequestMapping(value = "/register", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType register(@RequestParam(name="telphone") String telphone,
                                     @RequestParam(name = "otpCode")String otpCode,
                                     @RequestParam(name="name")String name,
                                     @RequestParam(name="gender")Integer gender,
                                     @RequestParam(name="age")Integer age,
                                     @RequestParam(name="password")String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {

        // 验证手机号和对应的otpCode相符合
        String inSessionOtpCode = (String) this.httpServletRequest.getSession().getAttribute(telphone);
        /* debug ：为什么session里没有值
        Enumeration<String> attributeNames = this.httpServletRequest.getSession().getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            String name1 = attributeNames.nextElement().toString();
            System.out.println(name1 + " " + this.httpServletRequest.getSession().getAttribute(name1));
        }
        System.out.println(this.httpServletRequest.getSession());
        System.out.println(otpCode + " " + inSessionOtpCode);
         */
        if(!StringUtils.equals(otpCode, inSessionOtpCode)) {
            throw new BusinessException(EmBussinessError.PARAMETER_VALIDATION_ERROR,"短信验证码不符合");
        }

        // 用户的注册流程
        UserModel userModel = new UserModel();
        userModel.setName(name);
        userModel.setGender(new Byte(String.valueOf(gender.intValue())));
        userModel.setAge(age);
        userModel.setTelephone(telphone);
        userModel.setRegisterMode("byphone");
        userModel.setEncrptPassword(this.EncodeByMd5(password));
        userService.register(userModel);
        return CommonReturnType.create(null); // 注册成功
    }

    public String EncodeByMd5(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        // 确定一个计算方法
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        BASE64Encoder base64Encoder = new BASE64Encoder();
        // 加密字符串
        String encode = base64Encoder.encode(md5.digest(str.getBytes("utf-8")));
        return encode;
    }

    // 用户获取otp短信接口
    @RequestMapping(value = "/getotp", method={RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType getOtp(@RequestParam(name="telphone")String telphone) {
        // 按照一定规则生成OTP验证码
        Random random = new Random();
        int randomInt = random.nextInt(99999);
        randomInt += 10000;
        String optCode = String.valueOf(randomInt);

        //将OTP验证码同对应用户手机号关联
        // 1、使用http session的方式绑定手机号与optcode
        // 2、(redis:覆盖value，保留最近的；且有过期时间，天然使用)
        httpServletRequest.getSession().setAttribute(telphone,optCode);

        //将OTP验证码通过短信通道发送给用户（省略）
        System.out.println("telephone = " + telphone + " &optCode = " + optCode);
        return CommonReturnType.create(null);
    }

    @RequestMapping("/get")
    @ResponseBody
    public UserModel getUser(@RequestParam(name="id") Integer id) {
        // url:localhost:8090/user/get?id=1
        // 调用service服务获取对应id的用户对象并返回给前端 （不是dataobject对象，是通过dataobject转化得到的model对象）
        // 此对象不应该直接显示到前端
        UserModel userModel = userService.getUserById(id);
        return userModel;
    }

    @RequestMapping("/get1")
    @ResponseBody
    public UserVO getUser1(@RequestParam(name="id") Integer id) {
        // url:localhost:8090/user/get?id=1
        // 调用service服务获取对应id的用户对象并返回给前端 （不是dataobject对象，是通过dataobject转化得到的model对象）
        // 此对象包含需要的信息直接显示到前端
        UserModel userModel = userService.getUserById(id);
        // 将核心领域模型用户对象转化为可供UI使用的viewobject
        return convertFromModel(userModel);
    }

    // 归一化返回
    @RequestMapping("/get2")
    @ResponseBody
    public CommonReturnType getUser2(@RequestParam(name="id") Integer id) throws BusinessException {
        // url:localhost:8090/user/get?id=1
        // 调用service服务获取对应id的用户对象并返回给前端 （不是dataobject对象，是通过dataobject转化得到的model对象）
        // 此对象包含需要的信息直接显示到前端
        UserModel userModel = userService.getUserById(id);

        // 若获取的对应用户信息不存在
        if(userModel == null) {
//            userModel.setEncrptPassword("123");
            throw new BusinessException(EmBussinessError.USER_NOT_EXIST);
        }

        // 将核心领域模型用户对象转化为可供UI使用的viewobject
        UserVO userVO = convertFromModel(userModel);
        // 返回通用对象
        return CommonReturnType.create(userVO);
    }

    private UserVO convertFromModel(UserModel userModel) {
        if(userModel == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        /*userVO.setId(userModel.getId());
        userVO.setName(userModel.getName());
        userVO.setAge(userModel.getAge());
        userVO.setGender(userModel.getGender());
        userVO.setTelephone(userModel.getTelephone());*/
        BeanUtils.copyProperties(userModel,userVO);
        return userVO;
    }
}
