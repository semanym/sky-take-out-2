package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.AddressBookService;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import io.swagger.util.Json;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    private Orders orders;
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //处理各种异常情况
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook==null)
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        ShoppingCart shoppingCart=new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        if(list==null||list.size()==0)
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        //订单表插入一条数据
        Orders orders=new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(BaseContext.getCurrentId());
        orders.setAddress(addressBook.getDetail());
        this.orders=orders;
        orderMapper.insert(orders);
        //订单详细表插入多条数据
        List<OrderDetail> orderDetailList=new ArrayList<>();
        for (ShoppingCart cart : list) {
            OrderDetail orderDetail=new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }

            orderDetailMapper.insertBatch(orderDetailList);


        //清空购物车
            shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
            OrderSubmitVO orderSubmitVO=OrderSubmitVO.builder().id(orders.getId()).orderAmount(orders.getAmount()).orderNumber(orders.getNumber())
                    .orderTime(orders.getOrderTime()).build();
        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

//        //调用微信支付接口，生成预支付交易单
//        JSONObject jsonObject = weChatPayUtil.pay(
//                ordersPaymentDTO.getOrderNumber(), //商户订单号
//                new BigDecimal(0.01), //支付金额，单位 元
//                "苍穹外卖订单", //商品描述
//                user.getOpenid() //微信用户的openid
//        );
//
//        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
//            throw new OrderBusinessException("该订单已支付");
//        }
        JSONObject jsonObject=new JSONObject();
        jsonObject.put("code","ORDERPAID");
        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        Integer OrderPaidStatus =Orders.PAID;
        Integer OrderStatus=Orders.TO_BE_CONFIRMED;
        LocalDateTime check_out_time=LocalDateTime.now();
        orderMapper.updateStatus(OrderStatus,OrderPaidStatus,check_out_time,this.orders.getId());
        paySuccess(ordersPaymentDTO.getOrderNumber());
        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
//        Orders orders = Orders.builder()
//                .id(ordersDB.getId())
//                .status(Orders.TO_BE_CONFIRMED)
//                .payStatus(Orders.PAID)
//                .checkoutTime(LocalDateTime.now())
//                .build();
//
//        orderMapper.update(orders);
        Map map=new HashMap();
        map.put("type",1);
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号:"+outTradeNo);
        String json= JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    @Override
    public PageResult pageQuery4User(int page, int pageSize, Integer status) {
        PageHelper.startPage(page,pageSize);
        OrdersPageQueryDTO ordersPageQueryDTO=new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);
        Page<Orders> pageQuery=orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> list=new ArrayList<>();
        if(pageQuery!=null&&pageQuery.getTotal()>0){
            for (Orders order : pageQuery) {
                Long orderId = order.getId();
                List<OrderDetail> orderDetails=orderDetailMapper.getByOrderId(orderId);
                OrderVO orderVO=new OrderVO();
                BeanUtils.copyProperties(order,orderVO);
                orderVO.setOrderDetailList(orderDetails);
                list.add(orderVO);
            }
        }
        return new PageResult(pageQuery.getTotal(),list);
    }

    @Override
    public OrderVO details(Long id) {
        Orders orders=orderMapper.getById(id);
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        OrderVO orderVO=new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    @Override
    public void userCancelById(Long id) throws Exception{
        Orders ordersDB = orderMapper.getById(id);
        if(ordersDB==null)
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        if(ordersDB.getStatus()>2){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Orders orders=new Orders();
        orders.setId(ordersDB.getId());
        if(ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    @Override
    public void repetition(Long id) {
        Long userId=BaseContext.getCurrentId();
        List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(id);
        List<ShoppingCart> shoppingCartList=orderDetailList.stream().map(x->{
            ShoppingCart shoppingCart=new ShoppingCart();
            BeanUtils.copyProperties(x,shoppingCart,"id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        Page<Orders> page=orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList=getOrderVOList(page);
        return new PageResult(page.getTotal(),orderVOList);
    }

    @Override
    public OrderStatisticsVO statistics() {
        Integer toBeConfirmed=orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed=orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress=orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);
        OrderStatisticsVO orderStatisticsVO=new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders.builder().id(ordersConfirmDTO.getId()).status(Orders.CONFIRMED).build();
        orderMapper.update(orders);
    }

    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        Orders ordersDB=orderMapper.getById(ordersRejectionDTO.getId());
        if(ordersDB==null||!ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED))
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        Integer paystatus=ordersDB.getPayStatus();
        ordersDB.setStatus(Orders.CANCELLED);
        ordersDB.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        ordersDB.setCancelTime(LocalDateTime.now());
        if(paystatus==Orders.PAID)
            ordersDB.setPayStatus(Orders.REFUND);
        orderMapper.update(ordersDB);
    }

    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        Orders ordersDB=orderMapper.getById(ordersCancelDTO.getId());
        Integer paystatus=ordersDB.getPayStatus();
        ordersDB.setCancelReason(ordersCancelDTO.getCancelReason());
        ordersDB.setStatus(Orders.CANCELLED);
        ordersDB.setCancelTime(LocalDateTime.now());
        if(paystatus==1)
            ordersDB.setPayStatus(Orders.REFUND);
        orderMapper.update(ordersDB);

    }

    @Override
    public void delivery(Long id) {
        Orders orderDB=orderMapper.getById(id);
        if(orderDB==null||!orderDB.getStatus().equals(Orders.CONFIRMED))
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        orderDB.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orderDB);
    }

    @Override
    public void complete(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        ordersDB.setStatus(Orders.COMPLETED);
        ordersDB.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(ordersDB);
    }

    @Override
    public void reminder(Long id) {
        Orders ordersDB=orderMapper.getById(id);
        if(ordersDB==null)
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        Map map=new HashMap();
        map.put("type",2);
        map.put("orderId",id);
        map.put("content","订单号:"+ordersDB.getNumber());
        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page){
        List<OrderVO> orderVOList=new ArrayList<>();
        List<Orders> ordersList = page.getResult();
        if(ordersList!=null&&ordersList.size()>0){
            for (Orders orders : ordersList) {
                OrderVO orderVO=new OrderVO();
                BeanUtils.copyProperties(orders,orderVO);
                String orderDishes=getOrderDishesStr(orders);
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }
    private String getOrderDishesStr(Orders orders){
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        List<String> orderDishList=orderDetailList.stream().map(x->{
            String orderDish=x.getName()+"*"+x.getNumber()+";";
            return orderDish;
        }).collect(Collectors.toList());
        return String.join("",orderDishList);
    }
}
