package com.cesar.bocana.ui.printing

/**
 * Define el FLUJO de trabajo que el usuario selecciona en el menú de etiquetas.
 * Esto ayuda a los fragmentos a decidir qué UI y lógica deben presentar.
 */
enum class LabelFlowType {
    SIMPLE,
    FIXED_DETAILED,
    VARIABLE_DETAILED
}
