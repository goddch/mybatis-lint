package com.example;

import lombok.Data;

/** #{...} で読めるプロパティの題材。Lombok の @Data で getter / setter を作っている。 */
@Data
public class MemberDomain {
    private Long memberId;
    private String memberName;
}
