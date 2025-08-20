package com.example.scheduler.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConvenienceStoreDto {
    private Long objtId;       // OBJT_ID
    private String fcltyCd;    // FCLTY_CD
    private String fcltyNm;    // FCLTY_NM
    private String adres;      // ADRES
    private String rnAdres;    // RN_ADRES
    private String sggCd;      // SGG_CD
    private String emdCd;      // EMD_CD
    private String ctprvnCd;   // CTPRVN_CD
    private String telno;      // TELNO
    private String fcltyTy;    // FCLTY_TY
    private Integer dataYr;    // DATA_YR
    private BigDecimal x;      // X
    private BigDecimal y;      // Y
    private String rawJson;    // 원문 json
}
