import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as groovy from '../services/groovy-helpers';
import type { PinnedObject } from '../types';

/**
 * Tree node for the Groovy Inspector. Mirrors the Object Browser's BrowserNode so
 * Java fields are drillable: each node carries a Groovy `path` and is lazily
 * re-evaluated via `groovy.evaluateAndDescribe` on expand.
 */
export interface InspectorNode {
  id: string;
  name: string;
  path: string;            // Groovy expression that produces this value
  className?: string;
  shortName?: string;
  value?: unknown;
  displayValue?: string;
  expandable: boolean;
  expanded: boolean;
  loading: boolean;
  children?: InspectorNode[];
  error?: string;
}

let nodeIdCounter = 0;
function generateNodeId(): string {
  return `inode_${++nodeIdCounter}`;
}

function formatDisplayValue(value: unknown, valueType: string): string {
  if (value === null || value === undefined) return 'null';

  if (valueType === 'string') {
    const str = String(value);
    if (str.length > 50) return `"${str.slice(0, 50)}..."`;
    return `"${str}"`;
  }

  if (valueType === 'primitive') {
    return String(value);
  }

  if (valueType === 'array') {
    if (Array.isArray(value)) return `Array[${value.length}]`;
    return 'Array';
  }

  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    if (obj.__class) {
      const cls = String(obj.__class);
      const shortName = cls.split('.').pop();
      if (obj.__toString) return `${shortName}: ${String(obj.__toString)}`;
      return shortName || 'Object';
    }
    return 'Object';
  }

  return String(value);
}

export const useInspectorStore = defineStore('inspector', () => {
  const rootObject = ref<InspectorNode | null>(null);
  const pinnedObjects = ref<PinnedObject[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const selectedNode = ref<InspectorNode | null>(null);
  const lastCode = ref<string>('');

  // Load pinned objects from localStorage
  const savedPinned = localStorage.getItem('debugbridge-pinned');
  if (savedPinned) {
    try {
      pinnedObjects.value = JSON.parse(savedPinned);
    } catch {
      // Ignore malformed storage
    }
  }

  function savePinned() {
    localStorage.setItem('debugbridge-pinned', JSON.stringify(pinnedObjects.value));
  }

  function generatePinId(): string {
    return `pin_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
  }

  function createChildNodes(info: groovy.ObjectInfo, parentPath: string): InspectorNode[] {
    const children: InspectorNode[] = [];

    for (const field of info.fields) {
      const fieldPath = `${parentPath}.${field.name}`;
      const isObject = field.valueType === 'object' || field.valueType === 'array';

      children.push({
        id: generateNodeId(),
        name: field.name,
        path: fieldPath,
        className: field.className,
        shortName: field.className?.split('.').pop(),
        value: field.value,
        displayValue: formatDisplayValue(field.value, field.valueType),
        expandable: isObject && field.expandable,
        expanded: false,
        loading: false,
      });
    }

    // Expandable objects first, then alphabetical — same as Object Browser
    children.sort((a, b) => {
      if (a.expandable && !b.expandable) return -1;
      if (!a.expandable && b.expandable) return 1;
      return a.name.localeCompare(b.name);
    });

    return children;
  }

  /**
   * Evaluate a Groovy expression and produce a drillable tree. The root is
   * auto-expanded (its direct fields are fetched); deeper levels are expanded
   * lazily on click via expandNode().
   */
  async function inspect(code: string, name?: string): Promise<void> {
    isLoading.value = true;
    error.value = null;
    lastCode.value = code;

    try {
      const info = await groovy.evaluateAndDescribe(code);
      const nodeName = name || info.shortName || 'result';
      // Wrap the user's code so child paths can reference it as a single value.
      const path = `{ -> ${code} }()`;

      const node: InspectorNode = {
        id: generateNodeId(),
        name: nodeName,
        path,
        className: info.className,
        shortName: info.shortName,
        displayValue: info.displayValue,
        expandable: !info.isNull && info.fields.length > 0,
        expanded: true,
        loading: false,
        children: info.isNull ? [] : createChildNodes(info, path),
      };

      rootObject.value = node;
      selectedNode.value = node;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      rootObject.value = null;
      selectedNode.value = null;
    } finally {
      isLoading.value = false;
    }
  }

  /**
   * Toggle expansion, fetching children on the first expand. Re-evaluates the
   * node's Groovy path so the view reflects the current runtime state.
   */
  async function expandNode(node: InspectorNode): Promise<void> {
    if (!node.expandable) return;

    if (node.children) {
      node.expanded = !node.expanded;
      return;
    }

    node.loading = true;
    node.error = undefined;

    try {
      const info = await groovy.evaluateAndDescribe(`return ${node.path}`);

      if (info.isNull) {
        node.children = [];
        node.displayValue = 'null';
      } else {
        node.children = createChildNodes(info, node.path);
        node.className = info.className;
        node.shortName = info.shortName;
        if (info.displayValue) {
          node.displayValue = info.displayValue;
        }
      }

      node.expanded = true;
    } catch (e) {
      node.error = e instanceof Error ? e.message : String(e);
    } finally {
      node.loading = false;
    }
  }

  function selectNode(node: InspectorNode) {
    selectedNode.value = node;
  }

  function pinObject(name: string, code: string, refId?: string): void {
    const existing = pinnedObjects.value.find(p => p.name === name);
    if (existing) {
      existing.code = code;
      if (refId) existing.refId = refId;
    } else {
      pinnedObjects.value.push({
        id: generatePinId(),
        name,
        code,
        refId: refId || '',
        className: rootObject.value?.className || '',
        pinnedAt: new Date(),
      });
    }
    savePinned();
  }

  function unpinObject(id: string): void {
    const idx = pinnedObjects.value.findIndex(p => p.id === id);
    if (idx >= 0) {
      pinnedObjects.value.splice(idx, 1);
      savePinned();
    }
  }

  function clear(): void {
    rootObject.value = null;
    selectedNode.value = null;
    error.value = null;
    lastCode.value = '';
  }

  return {
    rootObject,
    pinnedObjects,
    isLoading,
    error,
    selectedNode,
    lastCode,
    inspect,
    expandNode,
    selectNode,
    pinObject,
    unpinObject,
    clear,
  };
});
