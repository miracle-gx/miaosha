package com.miaoshaproject.dao;

import com.miaoshaproject.dataobject.PromoDO;
import org.apache.ibatis.annotations.Param;

public interface PromoDOMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    int deleteByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    int insert(PromoDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    int insertSelective(PromoDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    PromoDO selectByPrimaryKey(Integer id);
    PromoDO selectByItemId(Integer itemId);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    int updateByPrimaryKeySelective(PromoDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbg.generated Fri May 06 15:33:00 CST 2022
     */
    int updateByPrimaryKey(PromoDO record);
}