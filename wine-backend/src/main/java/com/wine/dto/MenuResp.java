package com.wine.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MenuResp {
    private Long barId;
    private String barName;
    private List<WineMenuItem> wines;

    @Data
    public static class WineMenuItem {
        private Long menuId;
        private Long wineSkuId;
        private String wineName;
        private String origin;
        private Integer vintage;
        private String grapeType;
        private String alcohol;
        private String coverImage;
        private String description;
        private Integer currentCapacity;
        private List<CupOption> cupOptions;
    }

    @Data
    public static class CupOption {
        private Integer volumeMl;
        private String volumeName;
        private BigDecimal price;
        private Integer slotNo;
        private Long dispenserId;
    }
}
