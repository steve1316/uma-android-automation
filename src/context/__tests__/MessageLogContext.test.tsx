import { act, renderHook } from "@testing-library/react-native"
import { useContext } from "react"
import { MessageLogDataContext, MessageLogDispatchContext, MessageLogProvider } from "../MessageLogContext"

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

    it("keeps only the newest 2000 lines", () => {
        const { result } = render()
        act(() => {
            for (let i = 1; i <= 2005; i++) result.current.dispatch.addMessageToLog(i, `line ${i}`)
            jest.advanceTimersByTime(300)
        })
        const log = result.current.data.messageLog
        expect(log).toHaveLength(2000)
        expect(log[0].id).toBe(6)
        expect(log[log.length - 1].id).toBe(2005)
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
