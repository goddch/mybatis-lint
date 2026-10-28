package com.example;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * mybatis-lint/unknown-parameter-property と mybatis-lint/missing-mapper-statement の題材。
 */
public interface MemberMapper {
    // 正常系: XML の #{memberId} / #{memberName} は MemberDomain のプロパティ。
    int insertMember(MemberDomain member);

    // 異常系: XML の #{memberNmae} は memberName のタイプミス(error)。
    int updateName(MemberDomain member);

    // 異常系: XML の #{member.memberNmae} は @Param("member") の型にないプロパティ(error)。
    int updateById(@Param("member") MemberDomain member, @Param("id") Long id);

    // 異常系: XML の foreach の #{m.memberNmae} は、要素の型 MemberDomain にないプロパティ(error)。
    int insertAll(@Param("members") List<MemberDomain> members);

    // 異常系: XML の id は findByName なので、このメソッドの statement がない(warning)。
    List<MemberDomain> findByNmae(String name);
}
