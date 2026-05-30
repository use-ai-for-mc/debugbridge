import { bridge } from './bridge';

/**
 * Helper functions that generate Groovy code for object inspection.
 *
 * The mod's `execute` endpoint runs Groovy; these helpers build small Groovy
 * snippets that return plain Maps/Lists, which the server serializes into the
 * `{type, value}` envelope that {@link unwrapBridgeValue} unwraps.
 */

export interface FieldInfo {
  name: string;
  type: string;
  value: unknown;
  valueType: 'primitive' | 'string' | 'object' | 'array' | 'null';
  className?: string;
  expandable: boolean;
  isStatic?: boolean;
  modifiers?: string;
}

export interface ObjectInfo {
  className: string;
  shortName: string;
  fields: FieldInfo[];
  methods: string[];
  superClass?: string;
  interfaces?: string[];
  isNull: boolean;
  displayValue?: string;
}

/**
 * Import a class and get its static fields/methods
 */
export async function inspectClass(className: string): Promise<ObjectInfo> {
  const code = `return java.describe(java.type("${className}"))`;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to inspect class');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result), className);
}

/**
 * Call a static method on a class
 */
export async function callStaticMethod(className: string, methodName: string, args: string[] = []): Promise<ObjectInfo> {
  const argsStr = args.join(', ');
  const code = `
    def cls = java.type("${className}")
    def result = cls.${methodName}(${argsStr})
    if (result == null) return [isNull: true]
    return java.describe(result)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to call method');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Get a field value from an object path
 */
export async function getFieldValue(basePath: string, fieldName: string): Promise<ObjectInfo> {
  const code = `
    def obj = ${basePath}
    if (obj == null) return [isNull: true]
    def value = obj.${fieldName}
    if (value == null) return [isNull: true]
    return java.describe(value)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to get field');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Call a method on an object
 */
export async function callMethod(basePath: string, methodName: string, args: string[] = []): Promise<ObjectInfo> {
  const argsStr = args.join(', ');
  const code = `
    def obj = ${basePath}
    if (obj == null) return [isNull: true]
    def result = obj.${methodName}(${argsStr})
    if (result == null) return [isNull: true]
    return java.describe(result)
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to call method');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Evaluate arbitrary Groovy and describe the result.
 *
 * java.describe(obj) returns { class, runtimeClass, superclass, interfaces,
 * fields = [ {name, type, static, final}, ... ] } — metadata only, no values.
 * We read each field's value off the object and package everything into the
 * shape parseObjectInfo expects. The whole walk runs inside `sync { }` so the
 * per-field reflective reads batch into a single game-thread hop.
 */
export async function evaluateAndDescribe(groovyCode: string): Promise<ObjectInfo> {
  const code = `
    return sync {
      def __obj = { -> ${groovyCode} }()
      if (__obj == null) return [isNull: true]

      def __result = [:]

      if (__obj instanceof Map) {
        // Plain map: surface its entries as fields.
        __result.__class = __obj.getClass().getName()
        def __fields = [:]
        int __fc = 0
        for (__e in __obj.entrySet()) {
          __fc++; if (__fc > 200) break
          def __v = __e.value
          def __fi = [type: (__v == null ? 'null' : __v.getClass().getSimpleName())]
          if (__v instanceof CharSequence) {
            def __s = __v.toString()
            __fi.value = __s.length() > 100 ? __s.substring(0, 100) + '...' : __s
          } else if (__v instanceof Number || __v instanceof Boolean) {
            __fi.value = __v
          } else if (__v != null) {
            __fi.value = [__class: __v.getClass().getName()]
          }
          __fields[String.valueOf(__e.key)] = __fi
        }
        __result.fields = __fields
      } else {
        def __desc = java.describe(__obj)
        __result.__class = String.valueOf(__desc['class'])
        __result.superClass = String.valueOf(__desc.superclass ?: '')
        def __fields = [:]
        int __fc = 0
        for (__f in (__desc.fields ?: [])) {
          __fc++; if (__fc > 200) break
          def __fi = [
            type: String.valueOf(__f.type ?: 'unknown'),
            modifiers: ((__f['static'] ? 'static ' : '') + (__f['final'] ? 'final' : ''))
          ]
          try {
            def __val = __obj."\${__f.name}"
            if (__val != null) {
              if (__val instanceof CharSequence) {
                def __s = __val.toString()
                __fi.value = __s.length() > 100 ? __s.substring(0, 100) + '...' : __s
                __fi.valueClass = 'java.lang.String'
              } else if (__val instanceof Number || __val instanceof Boolean) {
                __fi.value = __val
              } else {
                def __vc = java.typeName(__val)
                def __ts = String.valueOf(__val)
                __fi.valueClass = __vc
                __fi.value = [__class: __vc, __toString: (__ts.length() > 80 ? __ts.substring(0, 80) + '...' : __ts)]
              }
            }
          } catch (ignored) {}
          __fields[String.valueOf(__f.name)] = __fi
        }
        __result.fields = __fields
      }

      def __tstr = String.valueOf(__obj)
      __result.__toString = __tstr.length() > 80 ? __tstr.substring(0, 80) + '...' : __tstr
      return __result
    }
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to evaluate');
  }

  return parseObjectInfo(unwrapBridgeValue(result.result));
}

/**
 * Get array/collection elements
 */
export async function getCollectionElements(basePath: string, start: number = 0, limit: number = 50): Promise<{ items: ObjectInfo[], total: number }> {
  const code = `
    return sync {
      def obj = ${basePath}
      if (obj == null) return [items: [], total: 0]

      def all = java.list(obj)
      def total = all.size()
      def items = []
      int idx = 0
      for (el in all) {
        if (idx >= ${start} && idx < ${start + limit}) {
          if (el == null) {
            items << [isNull: true, index: idx]
          } else {
            def desc = java.describe(el)
            desc.index = idx
            items << desc
          }
        }
        idx++
      }
      return [items: items, total: total]
    }
  `;

  const result = await bridge.execute(code);
  if (!result.success) {
    throw new Error(result.error || 'Failed to get collection');
  }

  const data = result.result as { items: unknown[], total: number };
  return {
    items: data.items.map(item => parseObjectInfo(unwrapBridgeValue(item))),
    total: data.total
  };
}

/**
 * The mod's JSON serializer wraps every value in {type, value}.
 * Recursively unwrap these envelopes to get the plain data.
 */
function unwrapBridgeValue(data: unknown): unknown {
  if (data === null || data === undefined) return data;
  if (typeof data !== 'object' || Array.isArray(data)) return data;

  const obj = data as Record<string, unknown>;

  // Detect the {type, value} envelope: has exactly "type" and "value" keys
  // where "type" is a string like "table", "string", "number", "boolean", "nil"
  if ('type' in obj && 'value' in obj && typeof obj.type === 'string') {
    const t = obj.type as string;
    if (t === 'table') {
      return unwrapBridgeValue(obj.value);
    }
    if (t === 'string' || t === 'number' || t === 'boolean' || t === 'nil') {
      return obj.value;
    }
  }

  // A wrapped Java object that slipped through: keep a light summary.
  if (obj.type === 'object' && typeof obj.className === 'string') {
    return { __class: obj.className, __toString: obj.toString };
  }

  // Recursively unwrap object properties
  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(obj)) {
    result[key] = unwrapBridgeValue(val);
  }
  return result;
}

function parseObjectInfo(data: unknown, defaultClassName?: string): ObjectInfo {
  if (!data || typeof data !== 'object') {
    return {
      className: defaultClassName || 'unknown',
      shortName: defaultClassName?.split('.').pop() || 'unknown',
      fields: [],
      methods: [],
      isNull: true
    };
  }

  const obj = data as Record<string, unknown>;

  if (obj.isNull) {
    return {
      className: (obj.className as string) || defaultClassName || 'null',
      shortName: 'null',
      fields: [],
      methods: [],
      isNull: true
    };
  }

  const rawClass = obj.__class ?? obj.className ?? defaultClassName ?? 'Object';
  const className = typeof rawClass === 'string' ? rawClass : String(rawClass);
  const shortName = className.split('.').pop() || className;

  const fields: FieldInfo[] = [];
  const methods: string[] = [];

  // Parse fields from java.describe() output
  if (obj.fields && typeof obj.fields === 'object') {
    for (const [name, info] of Object.entries(obj.fields as Record<string, unknown>)) {
      const fieldData = info as Record<string, unknown>;
      fields.push({
        name,
        type: fieldData.type != null ? String(fieldData.type) : 'unknown',
        value: fieldData.value,
        valueType: determineValueType(fieldData.value),
        className: fieldData.valueClass != null ? String(fieldData.valueClass) : undefined,
        expandable: isExpandable(fieldData.value),
        modifiers: fieldData.modifiers != null ? String(fieldData.modifiers) : undefined
      });
    }
  }

  // If no structured fields, treat all non-__ properties as fields
  if (fields.length === 0) {
    for (const [key, value] of Object.entries(obj)) {
      if (key.startsWith('__')) continue;
      if (key === 'isNull' || key === 'className') continue;

      fields.push({
        name: key,
        type: typeof value,
        value,
        valueType: determineValueType(value),
        expandable: isExpandable(value)
      });
    }
  }

  // Parse methods
  if (obj.methods && Array.isArray(obj.methods)) {
    methods.push(...(obj.methods as string[]));
  }

  return {
    className,
    shortName,
    fields,
    methods,
    superClass: obj.superClass != null ? String(obj.superClass) : undefined,
    interfaces: obj.interfaces as string[],
    isNull: false,
    displayValue: obj.__toString != null ? String(obj.__toString) : undefined
  };
}

function determineValueType(value: unknown): FieldInfo['valueType'] {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'number' || typeof value === 'boolean') return 'primitive';
  if (Array.isArray(value)) return 'array';
  return 'object';
}

function isExpandable(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'object') return true;
  return false;
}
