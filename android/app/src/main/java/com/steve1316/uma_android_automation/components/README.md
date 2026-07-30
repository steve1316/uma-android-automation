# Components

This directory contains classes which act as an API for in-game elements such as buttons, checkboxes, etc.

## Table of Contents

- [Components](#components)
  - [Table of Contents](#table-of-contents)
  - [Adding Components](#adding-components)
  - [Button.kt](#buttonkt)
  - [Checkbox.kt](#checkboxkt)
  - [Components.kt](#componentskt)
  - [Dialog.kt](#dialogkt)
  - [Icon.kt](#iconkt)
  - [Label.kt](#labelkt)
  - [Radio.kt](#radiokt)

## Adding Components

Adding new components is very straight forward. Just go into the file for the type of component you'd like to add, and copy any of the other components in that file and update it with the correct template path.

The only exception is if you want to add a dialog, in which case there is one extra step. This process is described in the docstring at the top of `Dialog.kt`.

For `ComplexComponentInterface` and `MultiStateButtonInterface`, the only difference is that you need to add a list of `Template`. See `Button.kt::ButtonMenuBarHome` for an example of a `MultiStateButtonInterface`.

Templates under `assets/images/components/` are shared by all four scenarios. When one scenario renders a shared element differently, add a new suffixed template (e.g. `_mini`, `_alt`) alongside the original and point that scenario at it by overriding the corresponding `open val` on `Campaign` or `Training`. Never replace a shared template in place with a scenario-specific crop, since that silently breaks the other three scenarios without changing a single line of code.

## Button.kt

Clickable button elements.

Buttons can also have multiple states in which case the `MultiStateButtonInterface` should be used.

## Checkbox.kt

Clickable checkbox elements.

## Components.kt

This file defines the various interfaces used by components.

## Dialog.kt

Defines dialog (pop-up) objects on screen and contains functions for detecting and handling them.

These are a bit more complicated and can have various different layouts but all we really care about are the interactive elements within them such as buttons and checkboxes.

Instructions for adding new dialog objects and implementing a dialog handler are in the docstring at the top of the `Dialog.kt` file.

## Icon.kt

These are images which are typically not clickable, however they _do_ have click functionality; it just isn't their primary purpose. This is why we classify them as Icons instead of Buttons.

## Label.kt

Non-clickable regions of text on screen.

## Radio.kt

Clickable radio button components.
