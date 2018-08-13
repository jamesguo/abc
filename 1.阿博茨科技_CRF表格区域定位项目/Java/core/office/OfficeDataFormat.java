package com.abcft.pdfextract.core.office;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OfficeDataFormat {
    private static final Logger logger = LogManager.getLogger();

    enum Type {
        Number, String, Date,Other
    }

    public static Pair<String, Enum> dataFormt(short fmt) {
        String result = "";
        Type type = Type.Date;
        switch (fmt) {
            case 0:
                result = "General";
                type = Type.String;
                break;
            case 1:
            case 2:
                result = "0.0";
                type = Type.Number;
                break;
            case 3:
                result = "#,##0";
                type = Type.Number;
                break;
            case 4:
                result = "#,##0.00";
                type = Type.Number;
                break;
            case 9:
                result = "0%";
                type = Type.Number;
                break;
            case 10:
                result = "0.0%";
                type = Type.Number;
                break;
            case 11:
                result = "0.00E+00";
                type = Type.Number;
                break;
            case 12:
                result = "# ?/?";
                type = Type.Other;
                break;
            case 13:
                result = "# ??/??";
                type = Type.Other;
                break;
            case 14:
                result = "MM-dd-yy";
                break;
            case 15:
                result = "dd-MM-yy";
                break;
            case 16:
                result = "d-MM";
                break;
            case 17:
                result = "MM-yy";
                break;
            case 18:
                result = "hh:mm AM/PM";

                break;
            case 19:
                result = "hh:mm:ss AM/PM";
                break;
            case 20:
                result = "hh:mm";
                break;
            case 21:
                result = "hh:mm:ss";
                break;
            case 22:
                result = "M/d/yy h:mm";
                break;
            case 27:
                result = "yyyy年M月";
                break;
            case 28:
            case 29:
                result = "M月d日";
                break;
            case 30:
                result = "M/d/yy";
                break;
            case 31:
                result = "yyyy年M月d日";
                break;
            case 32:
                result = "hh時mm分";
                break;
            case 33:
                result = "hh時mm分ss秒";
                break;
            case 34:
                result = "上午/下午hh時mm分";
                break;
            case 35:
                result = "上午/下午hh時mm分ss秒";
                break;
            case 36:
                result = "yyyy年M月";
                break;
            case 37:
                result = "#,##0 ;(#,##0)";
                type = Type.Number;
                break;
            case 38:
                result = "#,##0";
                type = Type.Number;
                break;
            case 39:
                result = "#,##0.00";
                type = Type.Number;
                break;
            case 40:
                result = "#,##0.00";
                type = Type.Number;
                break;
            case 45:
                result = "mm:ss";
                break;
            case 46:
                result = "hh:mm:ss";
                break;
            case 47:
                result = "mmss.0";
                type = Type.Other;
                break;
            case 48:
                result = "##0.0E+0";
                type = Type.Number;
                break;
            case 49:
                result = "@";
                type = Type.Other;
                break;
            case 50:
                result = "yyyy年M月";
                break;
            case 51:
                result = "M月d日";
                break;
            case 52:
                result = "上午/下午hh時mm分\n" +
                        "\n" +
                        "yyyy年M月";
                break;
            case 53:
                result = "M月d日";
                break;
            case 54:
                result = "M月d日";
                break;
            case 55:
                result = "上午/下午hh時mm分";
                break;
            case 56:
                result = "上午/下午hh時mm分ss秒";
                break;
            case 57:
                result = "yyyy年M月";
                break;
            case 58:
                result = "M月d日";
                break;
            default:
                result = "";
                type = Type.Other;
                break;
        }
        return Pair.of(result, type);
    }

    public static String tansferJavaDateFormat(String format){
        if(StringUtils.equalsAnyIgnoreCase(format,"","general")){
            return "";
        }else if(StringUtils.contains(format,"yyyy/m/d")){
            return "yyyy/M/d";
        }else if(StringUtils.contains(format,"yyyy/mm/dd")){
            return "yyyy/MM/dd";
        }else if(StringUtils.contains(format,"yyyy-mm-dd")){
            return "yyyy-MM-dd";
        }else if(StringUtils.contains(format,"yyyy/m/d")){
            return "yyyy-M-d";
        }else if (StringUtils.contains(format,"yyyy\\-mm\\-dd")){
            return "yyyy-MM-dd";
        } else if(StringUtils.contains(format,"yyyy\\-mm")){
            return "yyyy-MM";
        }else if(StringUtils.contains(format,"yyyy/mm")){
            return "yyyy/MM";
        }else if (StringUtils.contains(format,"yyyy\\-m")){
            return "yyyy/M";
        } else if(StringUtils.contains(format,"yyyy")){
            return "yyyy";
        } else if(StringUtils.contains(format,"yy/mm/dd")) {
            return "yy/MM/dd";
        } else if(StringUtils.contains(format,"yy-mm-dd")){
            return "yy-MM-dd";
        }else if(StringUtils.contains(format,"yy/mm")){
            return "yy/MM";
        } else if(StringUtils.contains(format,"mmm/yy")){
            return "yyyy/MM";
        } else if(StringUtils.contains(format,"mm/dd")){
            return "MM/dd";
        }else if(StringUtils.contains(format,"mm-dd")){
            return "MM-dd";
        } else {
            return "yyyy/MM/dd";
        }
    }

}
