import React from 'react';

// Define the structure of a single rule
export interface Rule {
  id: string;
  fieldName: string;
  dataType: 'string' | 'number' | 'boolean' | 'date' | null;
  operator: '>' | '<' | '=' | null;
  value: any;
  color: string;
}

interface RuleRowProps {
  rule: Rule;
  orientation: 'horizontal' | 'vertical';
  onChange: (updatedRule: Rule) => void;
  onDelete: () => void;
}

// An array of available data types for the dropdown
const dataTypes: Array<Rule['dataType']> = ['string', 'number', 'boolean', 'date'];

// A map to determine which operators are valid for each data type
const validOperators: Record<NonNullable<Rule['dataType']>, Array<Rule['operator']>> = {
  string: ['='],
  number: ['=', '>', '<'],
  boolean: ['='],
  date: ['=', '>', '<'],
};

const InputWrapper: React.FC<{ children: React.ReactNode, label: string, orientation: 'horizontal' | 'vertical', className?: string }> = ({ children, label, orientation, className }) => (
  <div className={`${orientation === 'horizontal' ? 'min-w-0' : 'w-full'} flex flex-col ${className || ''}`}>
    <label className={`${orientation === 'horizontal' ? 'sr-only' : 'block'} text-xs font-bold text-gray-400 dark:text-gray-500 uppercase tracking-wide mb-1`}>
      {label}
    </label>
    {children}
  </div>
);

export const RuleRow = ({ rule, onChange, onDelete, orientation }: RuleRowProps) => {
  
  const handleTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newType = e.target.value as Rule['dataType'];
    let defaultValue: any = '';
    let operator: Rule['operator'] = null;

    if (newType === 'string') {
      operator = '=';
    } else if (newType === 'boolean') {
      defaultValue = false;
      operator = '=';
    } else if (newType === 'number') {
      defaultValue = 0;
    }

    onChange({ ...rule, dataType: newType, operator, value: defaultValue });
  };

  const renderValueInput = () => {
    const commonInputClasses = "w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500";
    
    if (rule.dataType === 'boolean') {
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
      case 'date':
        return <input type="date" value={rule.value} onChange={e => onChange({ ...rule, value: e.target.value })} className={`${commonInputClasses} dark:[color-scheme:dark]`} />;
      case 'number':
        return <input type="number" value={rule.value} onChange={e => onChange({ ...rule, value: e.target.value })} className={commonInputClasses} />;
      default: // string
        return <input type="text" value={rule.value} onChange={e => onChange({ ...rule, value: e.target.value })} className={commonInputClasses} />;
    }
  };

  const availableOperators = rule.dataType ? validOperators[rule.dataType] : [];

  // Progressive Disclosure Checks
  const showOperatorSelect = rule.dataType && rule.dataType !== 'string' && rule.dataType !== 'boolean';
  const showValue = !!rule.operator;
  const showColor = showValue && (rule.value !== '' && rule.value !== undefined && rule.value !== null);

  const layoutClasses = orientation === 'horizontal' 
    ? 'grid grid-cols-6 gap-2 items-start' 
    : 'flex flex-col gap-2';

  return (
    <div className={`${layoutClasses} p-2 rounded bg-black/10 dark:bg-black/20`}>
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
          {dataTypes.map(type => type && <option key={type} value={type}>{type}</option>)}
        </select>
      </InputWrapper>

      <InputWrapper label="Operator" orientation={orientation}>
        {showOperatorSelect ? (
          <select 
            value={rule.operator || ''} 
            onChange={e => onChange({ ...rule, operator: e.target.value as Rule['operator'] })}
            className="w-full p-2 rounded border text-sm bg-gray-700/50 dark:bg-gray-800/50 border-gray-600 dark:border-gray-700 text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="" disabled>Op</option>
            {availableOperators.map(op => op && <option key={op} value={op}>{op}</option>)}
          </select>
        ) : rule.dataType && (rule.dataType === 'string' || rule.dataType === 'boolean') ? (
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