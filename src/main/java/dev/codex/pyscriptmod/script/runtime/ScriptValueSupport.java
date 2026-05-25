package dev.codex.pyscriptmod.script.runtime;

import dev.codex.pyscriptmod.bridge.MinecraftBridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScriptValueSupport {
    private ScriptValueSupport() {
    }

    static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String string) {
            return !string.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalStateException("Expected number, got " + typeName(value));
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Expected integer, got " + typeName(value));
    }

    static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("Expected integer, got " + typeName(value));
    }

    static String asString(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    static Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return asString(left) + asString(right);
        }
        if (left instanceof Number && right instanceof Number) {
            if (isWhole(left) && isWhole(right)) {
                return asLong(left) + asLong(right);
            }
            return asDouble(left) + asDouble(right);
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            List<Object> merged = new ArrayList<>(leftList);
            merged.addAll(rightList);
            return merged;
        }
        throw new IllegalStateException("Unsupported operands for +: " + typeName(left) + " and " + typeName(right));
    }

    static Object subtract(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            if (isWhole(left) && isWhole(right)) {
                return asLong(left) - asLong(right);
            }
            return asDouble(left) - asDouble(right);
        }
        throw new IllegalStateException("Unsupported operands for -");
    }

    static Object multiply(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            if (isWhole(left) && isWhole(right)) {
                return asLong(left) * asLong(right);
            }
            return asDouble(left) * asDouble(right);
        }
        throw new IllegalStateException("Unsupported operands for *");
    }

    static Object divide(Object left, Object right) {
        return asDouble(left) / asDouble(right);
    }

    static Object mod(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            if (isWhole(left) && isWhole(right)) {
                return asLong(left) % asLong(right);
            }
            return asDouble(left) % asDouble(right);
        }
        throw new IllegalStateException("Unsupported operands for %");
    }

    static int compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(asDouble(left), asDouble(right));
        }
        if (left instanceof String leftString && right instanceof String rightString) {
            return leftString.compareTo(rightString);
        }
        throw new IllegalStateException("Unsupported comparison between " + typeName(left) + " and " + typeName(right));
    }

    static Object negate(Object value) {
        if (value instanceof Number number) {
            return isWhole(value) ? -number.longValue() : -number.doubleValue();
        }
        throw new IllegalStateException("Unsupported unary - for " + typeName(value));
    }

    static Object index(Object target, Object index) {
        if (target instanceof List<?> list) {
            return list.get(asInt(index));
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(index);
        }
        if (target instanceof String string) {
            return String.valueOf(string.charAt(asInt(index)));
        }
        throw new IllegalStateException("Value is not indexable: " + typeName(target));
    }

    static Object attr(Object target, String attribute) {
        if (target == null) {
            throw new IllegalStateException("Cannot access ." + attribute + " on None");
        }
        if (target instanceof ScriptRuntime.ScriptModuleValue moduleValue) {
            return moduleValue.exports().get(attribute);
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(attribute);
        }
        if (target instanceof MinecraftBridge.ScriptPos pos) {
            return switch (attribute) {
                case "x" -> pos.x();
                case "y" -> pos.y();
                case "z" -> pos.z();
                default -> null;
            };
        }
        if (target instanceof MinecraftBridge.ScriptEntity entity) {
            return switch (attribute) {
                case "id" -> entity.id();
                case "name" -> entity.name();
                case "handle" -> entity.handle();
                default -> null;
            };
        }
        if (target instanceof MinecraftBridge.ScriptPlayer player) {
            return switch (attribute) {
                case "name" -> player.name();
                case "handle" -> player.handle();
                default -> null;
            };
        }
        if (target instanceof MinecraftBridge.ScriptBlock block) {
            return switch (attribute) {
                case "id" -> block.id();
                default -> null;
            };
        }
        return null;
    }

    static Iterator<?> iterator(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().iterator();
        }
        throw new IllegalStateException("Value is not iterable: " + typeName(value));
    }

    static List<Object> buildRange(List<Object> args) {
        long start;
        long stop;
        long step;
        if (args.size() == 1) {
            start = 0;
            stop = asLong(args.get(0));
            step = 1;
        } else if (args.size() == 2) {
            start = asLong(args.get(0));
            stop = asLong(args.get(1));
            step = 1;
        } else if (args.size() == 3) {
            start = asLong(args.get(0));
            stop = asLong(args.get(1));
            step = asLong(args.get(2));
        } else {
            throw new IllegalStateException("range() expects 1 to 3 arguments");
        }

        if (step == 0) {
            throw new IllegalStateException("range() step cannot be 0");
        }

        List<Object> values = new ArrayList<>();
        if (step > 0) {
            for (long i = start; i < stop; i += step) {
                values.add(i);
            }
        } else {
            for (long i = start; i > stop; i += step) {
                values.add(i);
            }
        }
        return values;
    }

    static Map<Object, Object> dictFromPairs(List<Object> pairs) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.size(); i += 2) {
            map.put(pairs.get(i), pairs.get(i + 1));
        }
        return map;
    }

    static String typeName(Object value) {
        return value == null ? "None" : value.getClass().getSimpleName();
    }

    private static boolean isWhole(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;
    }

    static boolean equalsValue(Object left, Object right) {
        return Objects.equals(left, right);
    }
}
