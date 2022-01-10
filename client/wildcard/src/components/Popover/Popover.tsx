import { noop } from 'lodash'
import React, {
    createContext,
    forwardRef,
    MutableRefObject,
    useCallback,
    useContext,
    useMemo,
    useRef,
    useState,
} from 'react'
import FocusLock from 'react-focus-lock'
import { useCallbackRef, useMergeRefs } from 'use-callback-ref'

import { useOnClickOutside, useKeyboard } from '../../hooks'
import { ForwardReferenceComponent } from '../../types'

import { FloatingPanel, FloatingPanelProps } from './floating-panel/FloatingPanel'

enum PopoverOpenEventReason {
    TriggerClick = 'TriggerClick',
    TriggerFocus = 'TriggerFocus',
    TriggerBlur = 'TriggerBlur',
    ClickOutside = 'ClickOutside',
    Esc = 'Esc',
}

interface PopoverOpenEvent {
    isOpen: boolean
    reason: PopoverOpenEventReason
}

interface PopoverContextData {
    isOpen: boolean
    targetElement: HTMLElement | null
    tailElement: HTMLElement | null
    anchor?: MutableRefObject<HTMLElement | null>
    setOpen: (event: PopoverOpenEvent) => void
    setTargetElement: (element: HTMLElement | null) => void
    setTailElement: (element: HTMLElement | null) => void
}

const DEFAULT_CONTEXT_VALUE: PopoverContextData = {
    isOpen: false,
    targetElement: null,
    tailElement: null,
    setOpen: noop,
    setTargetElement: noop,
    setTailElement: noop,
}

const PopoverContext = createContext<PopoverContextData>(DEFAULT_CONTEXT_VALUE)

interface PopoverProps {
    anchor?: MutableRefObject<HTMLElement | null>
    open?: boolean
    onOpenChange?: (event: PopoverOpenEvent) => void
}

const Popover: React.FunctionComponent<PopoverProps> = props => {
    const { children, anchor, open, onOpenChange = noop } = props

    const [targetElement, setTargetElement] = useState<HTMLElement | null>(null)
    const [tailElement, setTailElement] = useState<HTMLElement | null>(null)

    const [isInternalOpen, setInternalOpen] = useState<boolean>(false)
    const isControlled = open !== undefined
    const isOpen = isControlled ? open : isInternalOpen
    const setOpen = useCallback(
        event => {
            if (!isControlled) {
                setInternalOpen(event)
            }

            onOpenChange(event)
        },
        [isControlled, onOpenChange]
    )

    const context = useMemo(
        () => ({
            isOpen,
            targetElement,
            tailElement,
            anchor,
            setOpen,
            setTargetElement,
            setTailElement,
        }),
        [isOpen, targetElement, tailElement, anchor, setOpen]
    )

    return <PopoverContext.Provider value={context}>{children}</PopoverContext.Provider>
}

interface PopoverTriggerProps {}

const PopoverTrigger = forwardRef((props, reference) => {
    const { as: Component = 'button', onClick = noop, ...otherProps } = props
    const { setTargetElement, setOpen, isOpen } = useContext(PopoverContext)

    const callbackReference = useCallbackRef<HTMLButtonElement>(null, setTargetElement)
    const mergedReference = useMergeRefs([reference, callbackReference])

    const handleClick: React.MouseEventHandler<HTMLButtonElement> = event => {
        setOpen({ isOpen: !isOpen, reason: PopoverOpenEventReason.TriggerClick })
        onClick(event)
    }

    return <Component ref={mergedReference} onClick={handleClick} {...otherProps} />
}) as ForwardReferenceComponent<'button', PopoverTriggerProps>

interface PopoverContentProps extends Omit<FloatingPanelProps, 'target' | 'marker'> {
    open?: boolean
}

const PopoverContent: React.FunctionComponent<PopoverContentProps> = props => {
    const { open, children, ...otherProps } = props
    const { isOpen, targetElement, anchor, setOpen } = useContext(PopoverContext)

    const reference = useRef<HTMLDivElement>(null)

    // Catch any outside click of popover element
    useOnClickOutside(reference, event => {
        if (targetElement?.contains(event.target as Node)) {
            return
        }

        setOpen({ isOpen: false, reason: PopoverOpenEventReason.ClickOutside })
    })

    // Close popover on escape
    useKeyboard({ detectKeys: ['Escape'] }, () => setOpen({ isOpen: false, reason: PopoverOpenEventReason.Esc }))

    if (!isOpen && !open) {
        return null
    }

    return (
        <FloatingPanel {...otherProps} ref={reference} target={anchor?.current ?? targetElement}>
            <FocusLock returnFocus={true}>{children}</FocusLock>
        </FloatingPanel>
    )
}

const Root = Popover
const Trigger = PopoverTrigger
const Content = PopoverContent

export {
    Root,
    Trigger,
    Content,
    //
    Popover,
    PopoverTrigger,
    PopoverContent,
    PopoverOpenEventReason,
}

export type { PopoverOpenEvent, PopoverProps, PopoverTriggerProps, PopoverContentProps }
