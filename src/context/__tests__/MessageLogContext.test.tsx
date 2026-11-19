import { act, renderHook } from "@testing-library/react-native"
import { useContext } from "react"
import { MAX_LOG_ENTRIES, MessageLogDataContext, MessageLogDispatchContext, MessageLogProvider } from "../MessageLogContext"

const useLog = () => ({ data: useContext(MessageLogDataContext), dispatch: useContext(MessageLogDispatchContext) })

const render = () => renderHook(useLog, { wrapper: MessageLogProvider })

describe("MessageLogProvider", () => {
    beforeEach(() => jest.useFakeTimers())
    afterEach(() => jest.useRealTimers())

    it("adds a burst of lines in one update after the flush delay", () => {
        const { result } = render()
        const before = result.current.data.messageLog
        act(() => {
            result.current.dispatch.addMessageToLog(1, "a")
            result.current.dispatch.addMessageToLog(2, "b")
            result.current.dispatch.addMessageToLog(3, "c")
        })
        expect(result.current.data.messageLog).toBe(before)
        act(() => jest.advanceTimersByTime(300))
        expect(result.current.data.messageLog.map((e) => e.message)).toEqual(["a", "b", "c"])
        act(() => {
            result.current.dispatch.addMessageToLog(4, "d")
            jest.advanceTimersByTime(300)
        })
        expect(result.current.data.messageLog.map((e) => e.message)).toEqual(["a", "b", "c", "d"])
    })

    it("keeps the newest lines and marks where older ones were hidden", () => {
        const { result } = render()
        act(() => {
            for (let i = 1; i <= MAX_LOG_ENTRIES + 5; i++) result.current.dispatch.addMessageToLog(i, `00:00:01.${String(i % 1000).padStart(3, "0")} line ${i}`)
            jest.advanceTimersByTime(300)
        })
        const log = result.current.data.messageLog
        expect(log).toHaveLength(MAX_LOG_ENTRIES)
        expect(log[0].message).toContain("6 earlier lines hidden")
        expect(log[0].id).toBe(6.5)
        expect(log[0].message.startsWith(`\n${log[1].message.slice(0, 12)}`)).toBe(true)
        expect(log[1].id).toBe(7)
        expect(log[log.length - 1].id).toBe(MAX_LOG_ENTRIES + 5)
    })

    it("keeps the settings block at the top and counts hidden lines across flushes", () => {
        const { result } = render()
        const settings = ["Started at 23:20:23.577.", "\n[SETTINGS] Current Bot Configuration:", "=====================================", "Run Mode: Career", "=====================================\n"]
        act(() => {
            settings.forEach((line, i) => result.current.dispatch.addMessageToLog(i + 1, `00:00:00.510 [INFO] ${line}`))
            for (let i = 6; i <= MAX_LOG_ENTRIES + 10; i++) result.current.dispatch.addMessageToLog(i, `00:00:02.000 line ${i}`)
            jest.advanceTimersByTime(300)
        })
        act(() => {
            for (let i = MAX_LOG_ENTRIES + 11; i <= MAX_LOG_ENTRIES + 20; i++) result.current.dispatch.addMessageToLog(i, `00:00:03.000 line ${i}`)
            jest.advanceTimersByTime(300)
        })
        const log = result.current.data.messageLog
        expect(log).toHaveLength(MAX_LOG_ENTRIES)
        expect(log.slice(0, 5).map((e) => e.id)).toEqual([1, 2, 3, 4, 5])
        expect(log.filter((e) => !Number.isInteger(e.id))).toHaveLength(1)
        expect(log[5].message).toContain("21 earlier lines hidden")
        expect(log[log.length - 1].id).toBe(MAX_LOG_ENTRIES + 20)
    })

    it("drops lines still waiting when the log is cleared", () => {
        const { result } = render()
        act(() => {
            result.current.dispatch.addMessageToLog(1, "old")
            result.current.dispatch.setMessageLog([])
            jest.advanceTimersByTime(300)
        })
        expect(result.current.data.messageLog).toEqual([])
        act(() => {
            result.current.dispatch.addMessageToLog(2, "new")
            jest.advanceTimersByTime(300)
        })
        expect(result.current.data.messageLog.map((e) => e.message)).toEqual(["new"])
    })
})
