package com.sqlsplice;

/**
 * 构造在被调试 JVM 中执行的自包含 Java 表达式。
 * <p>
 * 不能直接调用插件自身的 {@link SqlSplicer}：插件类加载器与被调试应用的类加载器相互隔离。
 */
public final class DebugExpressionBuilder {
    private DebugExpressionBuilder() {
    }

    public static String expression(String sqlVariable, String argsVariable) {
        if (!isJavaIdentifier(sqlVariable) || !isJavaIdentifier(argsVariable)) {
            throw new IllegalArgumentException("The debug variable name is not a legal Java identifier");
        }
        return "new Object() {"
                + "String q(String s){return \"'\"+s.replace(\"'\",\"''\")+\"'\";}"
                + "String join(java.util.Iterator it){StringBuilder b=new StringBuilder();while(it.hasNext()){if(b.length()>0)b.append(\", \");b.append(lit(it.next()));}if(b.length()==0)throw new IllegalArgumentException(\"An empty collection cannot be rendered as a SQL argument\");return b.toString();}"
                + "String array(Object a){int n=java.lang.reflect.Array.getLength(a);if(n==0)throw new IllegalArgumentException(\"An empty array cannot be rendered as a SQL argument\");StringBuilder b=new StringBuilder();for(int i=0;i<n;i++){if(i>0)b.append(\", \");b.append(lit(java.lang.reflect.Array.get(a,i)));}return b.toString();}"
                + "String lit(Object v){if(v==null)return \"NULL\";if(v instanceof CharSequence||v instanceof Character||v instanceof Enum)return q(String.valueOf(v));if(v instanceof Boolean)return ((Boolean)v).booleanValue()?\"TRUE\":\"FALSE\";if(v instanceof Number)return String.valueOf(v);if(v instanceof java.sql.Timestamp)return q(new java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\").format((java.util.Date)v));if(v instanceof java.sql.Date)return q(new java.text.SimpleDateFormat(\"yyyy-MM-dd\").format((java.util.Date)v));if(v instanceof java.sql.Time)return q(new java.text.SimpleDateFormat(\"HH:mm:ss\").format((java.util.Date)v));if(v instanceof java.util.Date)return q(new java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\").format((java.util.Date)v));if(v instanceof java.time.LocalDateTime)return q(java.time.format.DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm:ss\").format((java.time.LocalDateTime)v));if(v instanceof java.time.LocalDate)return q(v.toString());if(v instanceof java.time.LocalTime)return q(java.time.format.DateTimeFormatter.ofPattern(\"HH:mm:ss\").format((java.time.LocalTime)v));if(v instanceof java.util.Collection)return join(((java.util.Collection)v).iterator());if(v.getClass().isArray())return array(v);return q(String.valueOf(v));}"
                + "int p(String s){boolean sq=false,dq=false,line=false,block=false;for(int i=0;i<s.length();i++){char c=s.charAt(i),n=i+1<s.length()?s.charAt(i+1):0;if(line){if(c=='\\n'||c=='\\r')line=false;continue;}if(block){if(c=='*'&&n=='/'){block=false;i++;}continue;}if(sq){if(c=='\\''&&n=='\\'')i++;else if(c=='\\'')sq=false;continue;}if(dq){if(c=='\\\"'&&n=='\\\"')i++;else if(c=='\\\"')dq=false;continue;}if(c=='-'&&n=='-'){line=true;i++;}else if(c=='/'&&n=='*'){block=true;i++;}else if(c=='\\'')sq=true;else if(c=='\\\"')dq=true;else if(c=='?')return i;}return -1;}"
                + "String splice(Object rawSql,Object args){if(rawSql==null||args==null)throw new IllegalArgumentException(\"SQL and arguments must not be null\");String sql=String.valueOf(rawSql);java.util.Iterator it;if(args instanceof java.util.Collection)it=((java.util.Collection)args).iterator();else if(args.getClass().isArray()){java.util.ArrayList x=new java.util.ArrayList();for(int i=0;i<java.lang.reflect.Array.getLength(args);i++)x.add(java.lang.reflect.Array.get(args,i));it=x.iterator();}else throw new IllegalArgumentException(\"Arguments must be an array or collection\");while(it.hasNext()){int i=p(sql);if(i<0)throw new IllegalArgumentException(\"More arguments were supplied than SQL placeholders\");sql=sql.substring(0,i)+lit(it.next())+sql.substring(i+1);}if(p(sql)>=0)throw new IllegalArgumentException(\"Unreplaced SQL placeholder remains\");return sql;}"
                + "}.splice(" + sqlVariable + "," + argsVariable + ")";
    }

    private static boolean isJavaIdentifier(String value) {
        if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
