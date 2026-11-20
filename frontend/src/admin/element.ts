/** 后台在两端共用组件之外额外用到的 Element Plus 组件（按需注册，见 src/shared/element.ts） */
import type { Component } from 'vue'
import {
  ElCheckbox, ElCheckboxGroup, ElCollapse, ElCollapseItem, ElDatePicker, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElSwitch, ElTable, ElTableColumn,
} from 'element-plus'

import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/dropdown/style/css'
import 'element-plus/es/components/dropdown-menu/style/css'
import 'element-plus/es/components/dropdown-item/style/css'
import 'element-plus/es/components/date-picker/style/css'
import 'element-plus/es/components/collapse/style/css'
import 'element-plus/es/components/collapse-item/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/checkbox-group/style/css'

export const ADMIN_ELEMENTS: Component[] = [
  ElTable, ElTableColumn, ElDropdown, ElDropdownMenu, ElDropdownItem, ElDatePicker,
  ElCollapse, ElCollapseItem, ElSwitch, ElCheckbox, ElCheckboxGroup,
]
