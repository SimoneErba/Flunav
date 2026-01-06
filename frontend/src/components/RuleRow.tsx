import React, { useState } from 'react';
import { DisplayRule, DisplayRuleDataTypeEnum, DisplayRuleOperatorEnum } from '../api-client';

interface RuleRowProps {
  rule: DisplayRule;
  orientation: 'horizontal' | 'vertical';
  onChange: (updatedRule: DisplayRule) => void;
  onDelete: () => void;
  dragHandleProps?: React.HTMLAttributes<HTMLDivElement>; 
}

// A map to determine which operators are valid for each data type
const validOperators: Record<NonNullable<DisplayRuleDataTypeEnum>, Array<DisplayRuleOperatorEnum>> = {
  STRING: [DisplayRuleOperatorEnum.Equal],
  NUMBER: [DisplayRuleOperatorEnum.Equal, DisplayRuleOperatorEnum.Greater, DisplayRuleOperatorEnum.Lesser],
  BOOLEAN: [DisplayRuleOperatorEnum.Equal],
  DATETIME: [DisplayRuleOperatorEnum.Equal, DisplayRuleOperatorEnum.Greater, DisplayRuleOperatorEnum.Lesser],
};

const InputWrapper: React.FC<{ children: React.ReactNode, label: string, orientation: 'horizontal' | 'vertical', className?: string }> = ({ children, label, orientation, className }) => (
  <div className={`${orientation === 'horizontal' ? 'min-w-0' : 'w-full'} flex flex-col ${className || ''}`}>
    <label className={`${orientation === 'horizontal' ? 'sr-only' : 'block'} text-xs font-bold text-gray-400 dark:text-gray-500 uppercase tracking-wide mb-1`}>
      {label}
    </label>
    {children}
  </div>
);

export const RuleRow = ({ rule, onChange, onDelete, orientation, dragHandleProps }: RuleRowProps) => {
  const [isCollapsed, setIsCollapsed] = useState(orientation === 'vertical');

  const handleTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newType = e.target.value as DisplayRuleDataTypeEnum;
    let defaultValue: any = '';
    let operator: DisplayRuleOperatorEnum = null;

    if (newType === DisplayRuleDataTypeEnum.String) {
      operator = DisplayRuleOperatorEnum.Equal;
    } else if (newType === DisplayRuleDataTypeEnum.Boolean) {
      defaultValue = false;
      operator = DisplayRuleOperatorEnum.Equal;
    } else if (newType === DisplayRuleDataTypeEnum.Number) {
      defaultValue = 0;
    }

    onChange({ ...rule, dataType: newType, operator, value: defaultValue });
  };

  const renderValueInput = () => {
    const commonInputClasses = "w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500";
    
    if (rule.dataType === DisplayRuleDataTypeEnum.Boolean) {
      return (
        <div className="flex items-center justify-center h-10 px-2 bg-gray-700/50 dark:bg-gray-800/50 border border-gray-600 dark:border-gray-700 rounded">
          <input
            type="checkbox"
            checked={!!rule.value}
            onChange={e => onChange({ ...rule, value: e.target.checked })}
            className="h-5 w-5 rounded accent-blue-500 cursor-pointer"
          />
        </div>
      );
    }

    switch (rule.dataType) {
      case DisplayRuleDataTypeEnum.Datetime:
        return <input type="date" value={rule.value as any} onChange={e => onChange({ ...rule, value: e.target.value })} className={`${commonInputClasses} dark:[color-scheme:dark]`} />;
      case DisplayRuleDataTypeEnum.Number:
        return <input type="number" value={rule.value as any} onChange={e => onChange({ ...rule, value: e.target.value })} className={commonInputClasses} />;
      default: // string
        return <input type="text" value={rule.value as any} onChange={e => onChange({ ...rule, value: e.target.value })} className={commonInputClasses} />;
    }
  };

  const availableOperators = rule.dataType ? validOperators[rule.dataType] : [];

  // Progressive Disclosure Checks
  const showOperatorSelect = rule.dataType && rule.dataType !== DisplayRuleDataTypeEnum.String && rule.dataType !== DisplayRuleDataTypeEnum.Boolean;
  const showValue = !!rule.operator;
  const showColor = showValue && (rule.value !== '' && rule.value !== undefined && rule.value !== null);

  const layoutClasses = orientation === 'horizontal' 
    ? 'grid grid-cols-[auto_1fr_1fr_1fr_1fr_auto_auto] gap-2 items-start' 
    : 'flex flex-col gap-2';
  
  // -- Vertical Collapsed View --
  if (orientation === 'vertical' && isCollapsed) {
    return (
      <div className="flex items-center gap-2 p-2 rounded bg-black/10 dark:bg-black/20 border border-transparent hover:border-gray-600 transition-colors">
         {/* Drag Handle */}
         <div {...dragHandleProps} className="cursor-grab text-gray-400 hover:text-white p-1">
          ⋮⋮
        </div>

        <div 
            className="flex-1 flex items-center gap-2 cursor-pointer overflow-hidden"
            onClick={() => setIsCollapsed(false)}
        >
            <div className="w-3 h-3 rounded-full shrink-0 border border-gray-500" style={{ backgroundColor: rule.color || '#fff' }}></div>
            <div className="flex flex-col min-w-0">
                <span className="text-xs font-bold text-gray-300 truncate">{rule.fieldName || 'New Rule'}</span>
                <span className="text-[10px] text-gray-500 truncate">
                    {rule.operator === DisplayRuleOperatorEnum.Equal ? '=' : rule.operator} {String(rule.value || '')}
                </span>
            </div>
        </div>

        <button onClick={onDelete} className="p-1 text-gray-500 hover:text-red-400 transition-colors">
            ✕
        </button>
      </div>
    );
  }

  // -- Expanded / Horizontal View --
  return (
    <div className={`${layoutClasses} p-2 rounded bg-black/10 dark:bg-black/20 relative group`}>
       {/* Drag Handle (Horizontal: First col, Vertical: Absolute top-right or separate row) */}
        {orientation === 'horizontal' ? (
             <div {...dragHandleProps} className="flex items-center justify-center h-10 cursor-grab text-gray-400 hover:text-white">
                ⋮⋮
            </div>
        ) : (
            <div className="flex justify-between items-center mb-1">
                 <div {...dragHandleProps} className="cursor-grab text-gray-400 hover:text-white p-1">
                    ⋮⋮
                </div>
                <button 
                    onClick={() => setIsCollapsed(true)} 
                    className="text-xs text-blue-400 hover:text-blue-300"
                >
                    Collapse ▲
                </button>
            </div>
        )}

      <InputWrapper label="Field" orientation={orientation}>
        <input 
          type="text" 
          placeholder="Field Name"
          value={rule.fieldName}
          onChange={e => onChange({ ...rule, fieldName: e.target.value })}
          className="w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </InputWrapper>

      <InputWrapper label="Type" orientation={orientation}>
        <select 
          value={rule.dataType || ''} 
          onChange={handleTypeChange}
          className="w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="" disabled>Select Type</option>
          {Object.values(DisplayRuleDataTypeEnum).map(type => type && <option key={type} value={type}>{type}</option>)}
        </select>
      </InputWrapper>

      <InputWrapper label="Operator" orientation={orientation}>
        {showOperatorSelect ? (
          <select 
            value={rule.operator || ''} 
            onChange={e => onChange({ ...rule, operator: e.target.value as DisplayRuleOperatorEnum })}
            className="w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="" disabled>Op</option>
            {availableOperators.map(op => op && <option key={op} value={op}>{op}</option>)}
          </select>
        ) : rule.dataType && (rule.dataType === DisplayRuleDataTypeEnum.String || rule.dataType === DisplayRuleDataTypeEnum.Boolean) ? (
            <div className="h-10 w-full flex items-center justify-center rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-gray-400">
                =
            </div>
        ) : <div className="h-10 w-full"></div>}
      </InputWrapper>
      
      <InputWrapper label="Value" orientation={orientation}>
        {showValue ? renderValueInput() : <div className="h-10 w-full"></div>}
      </InputWrapper>
      
      <InputWrapper label="Color" orientation={orientation} className="relative">
        {showColor ? (
            <div className="relative w-10 h-10 flex items-center justify-center">
                <div className="w-8 h-8 rounded-full border border-gray-500" style={{ backgroundColor: rule.color }}></div>
                <input 
                    type="color" 
                    value={rule.color}
                    onChange={e => onChange({ ...rule, color: e.target.value })}
                    className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
                    title="Select color"
                />
            </div>
        ) : <div className="h-10 w-full"></div>}
      </InputWrapper>

      <div className="flex items-center justify-end h-full">
        <button onClick={onDelete} className="p-2 h-10 rounded bg-red-900/50 hover:bg-red-900/80 text-red-300 transition-colors">
          🗑️
        </button>
      </div>
    </div>
  );
};