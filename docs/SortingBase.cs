using EasyAirport.Common.DataAccess.Entities;
using EasyAirport.Common.DataAccess.GW;
using EasyAirport.Common.Messages;
using EasyAirport.Common.Utilities;
using EasyAirport.Common.Utilities.Log;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.Data;
using System.Globalization;
using System.Linq;
using System.Text;
using ItemSecurity = EasyAirport.Common.DataAccess.GW.ItemSecurity;

namespace EasyAirport.Common.DataAccess.DB
{
    public class SortingBase : ISorting
    {
        //string connectionstring;
        private readonly IServiceScopeFactory _scopeFactory;
        protected ITrackingDAL _trackingDAL = null;
        protected ISortingDAL _sortingDAL = null;
        protected IConfigGlobal GlobalConf = null;
        protected IEBS _EBS = null;
        protected IEBSDAL _EBSDAL = null;
        protected IBPM _BPM = null;
        protected IClock _clock = null;
        protected ILogging _logger = null;
        protected IBaseUtils _baseUtils;
        protected SortedEventTypes _sortedEventTypes = null;
        protected bool bOCR = false;
        protected bool bMES = false;
        protected bool bEBS = false;

        private Dictionary<string, string> _LogbookEventTypesLoc = null;
        private Dictionary<string, string> _ChronoEventTypesLoc = null;

        protected void WLog(Severity level, string messageTag, params object[] pars)
        {
            _logger.WriteLog("ProcessWorker", Environment.MachineName, string.Empty, level, messageTag, pars);
        }

        public SortingBase(IConfiguration iconf, ITrackingDAL trackingDAL, IConfigGlobal _GlobalConf, IEBS EBS, IEBSDAL EBSDAL, IBPM BPM, ISortingDAL SortingDAL, IClock clock, ILogging logger, IBaseUtils baseUtils, IServiceScopeFactory scopeFactory)
        {
            //connectionstring = iconf.GetConnectionString("SACDB");
            //connectionstring = iconf["Data:SACDBConnection:ConnectionString"];
            _scopeFactory = scopeFactory;
            _trackingDAL = trackingDAL;
            _sortingDAL = SortingDAL;
            _BPM = BPM;
            _EBSDAL = EBSDAL;
            GlobalConf = _GlobalConf;
            _EBS = EBS;
            _clock = clock;
            _logger = logger;
            _baseUtils = baseUtils;

            bOCR = iconf.GetValue<bool>("WorkersSpecificParams:ProcessWorker:OCR");
            bMES = iconf.GetValue<bool>("WorkersSpecificParams:ProcessWorker:MES");
            bEBS = iconf.GetValue<bool>("WorkersSpecificParams:ProcessWorker:EBS");

            _sortedEventTypes = new SortedEventTypes(SortingDAL);
        }

        public SortingBase(string connString)
        {
            //connectionstring = connString;
        }

        protected bool CheckMESReasonEnabled(string reason, string FLC = null)
        {
            var Enabled = (bool)(GlobalConf[reason.ToUpper() + "_TO_MES_ENABLED"] ?? true);

            if (Enabled && FLC != null)
                Enabled = _trackingDAL.CheckConfigAirlineMESTypeEnabled(reason, FLC);

            return Enabled;
        }

        private int OCR(SortingEdgeData TP, DataAccess.GW.ITrackingEvents tracker, GW.Item item, LAP.BaseMessage destReq, ref List<TargetSortData> SelectedTargets, out string errMessage)
        {
            int iSentToOCR = 0;
            if (TP.isOCR && bOCR)
            {
                // if the current DP (TP) is a ATR with OCR capabilities, then the bag is queued to OCR
                // a negative ID of the OCR Proc queue is returned to caller in order to publish the request to OCR queue
                iSentToOCR = _sortingDAL.ManageOCR(item, TP, item.itemBarcodes.Where(b => b.IsValid).Select(b => b.Barcode).ToList(), destReq, out errMessage);

                if (iSentToOCR != 0)
                {
                    GW.ScannerStatistics scStat = new GW.ScannerStatistics();
                    scStat.TPid = TP.idATRTP;
                    scStat.IDItem = item?.ID;
                    scStat.SentToOCR = true;
                    _sortingDAL.InsertScannerStatistics(scStat, out errMessage);
                    tracker.WriteMessage(TrackingLevel.Debug, "QUEUED_OCR", iSentToOCR);
                    return -iSentToOCR;
                }
            }
            else
            {
                // if we are on normal TP (not with ATR with OCR capabilities) we look for the closest TP with OCR, if any
                SelectedTargets = _sortingDAL.GetClosestOCR(TP.EdgeStartID, out errMessage);
                if (SelectedTargets?.Any() ?? true)
                    tracker.WriteMessage(TrackingLevel.Debug, "TARGET_OCR", string.Join(',', SelectedTargets.Select(ft => ft.Target).ToList()));
            }

            // if bag wasn't sent to OCR (maybe because already attempted on that ATR) and no routing is available to OCR TPs
            // the bag is sent to MES

            var BSM = item.idBSM == null ? null : _sortingDAL.GetBSM((int)item.idBSM, out errMessage);
            var FLC = BSM?.FLC;

            if (iSentToOCR == 0 && SelectedTargets?.Any() != true && CheckMESReasonEnabled(item.BagDestinationStatus, FLC))
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);

                tracker.WriteMessage(TrackingLevel.Debug, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
            }

            return 0;
        }

        public virtual bool SetResult(GW.Item item, LAP.SortResultMsg sortResult, DataAccess.GW.ITrackingEvents trackEvents, out bool bFinalTarget, out SorterStart startSorterCommand, out string errMessage)
        {
            errMessage = string.Empty;
            bool bRet, bRet2;
            bFinalTarget = false;
            trackEvents.SetItem(item);
            string TPName = string.Empty;
            startSorterCommand = null;
            bool BPMPending;
            int? TPid = null;
            SortingEdgeData TPend = null;
            string sorterEnd = string.Empty;
            int ComponentIndexEnd = -1;
            bool bCompletedIndividualSort = false;
            string IndSortSpecialSort = string.Empty;
            string SortingReason = string.Empty;
            string SortingReasonSpecial = string.Empty;
            int compIndex = 0;
            GW.Component c = null;

            GW.ItemResult ir = new GW.ItemResult(sortResult);
            ir.IDItem = item.ID;

            if (sortResult.Destination != 0)
                compIndex = sortResult.Destination;
            else
                compIndex = sortResult.CheckPoint;

            TPid = _trackingDAL.TPfromComponent(compIndex, sortResult.Envelope.SourceNode);
            trackEvents.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After TPFromComponent {TPid}");

            if (TPid.HasValue)
            {
                ir.IDTrackingPoint = TPid.Value;
                TPend = _trackingDAL.GetTPbyEndID(TPid.Value);
                sorterEnd = string.Empty;
                c = _trackingDAL.GetComponentByTP(TPid.Value, out errMessage);
                if (c != null)
                {
                    ComponentIndexEnd = c.ComponentIndex;
                }
            }

            // SORT RESULT - Sorted
            if (sortResult.Result == 1)
            {
                trackEvents.WriteMessage(TrackingLevel.Info, "ItemSorted");

                bFinalTarget = _trackingDAL.isFinalTarget(sortResult.Destination, out errMessage);
                trackEvents.WriteMessage(TrackingLevel.Debug, "GENERIC", $"Type {sortResult.Type} - FinalTarget {bFinalTarget}");
                if (sortResult.Type == 1)
                {
                    // look for timestamp of the relevant preliminary sort
                    DateTime BPMTimestamp = _trackingDAL.GetItemResultTimestamp(item.ID, sortResult.Destination, out errMessage);

                    if (bFinalTarget)
                    {
                        // it's Final target and final result from PLC
                        trackEvents.WriteMessage(TrackingLevel.Info, "CreateBPM", "Sorted", item.ID, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.Destination, sortResult.Destination, BPMTimestamp.ToString("o", CultureInfo.InvariantCulture));
                        bRet = _BPM.CreateBPMForComponent("Sorted", item, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.Destination, sortResult.Destination, null, BPMTimestamp, out BPMPending, out errMessage);
                        trackEvents.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, sortResult.Destination, errMessage);

                        bool bRetCustomAction = FinalSortCustomAction(item, sortResult, trackEvents, out errMessage);
                        if (!bRetCustomAction)
                            trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "FinalSortCustomAction", errMessage);

                    }
                    else
                    {
                        trackEvents.WriteMessage(TrackingLevel.Info, "CreateBPM", "Movement", item.ID, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.CheckPoint, sortResult.Destination, BPMTimestamp.ToString("o", CultureInfo.InvariantCulture));
                        bRet = _BPM.CreateBPMForComponent("Movement", item, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.CheckPoint, sortResult.Destination, null, BPMTimestamp, out BPMPending, out errMessage);
                        trackEvents.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, sortResult.Destination, errMessage);

                        bool bRetCustomAction = IntermediateSortCustomAction(item, sortResult, trackEvents, TPend, ref SortingReason, ref SortingReasonSpecial, out errMessage);
                        if (!bRetCustomAction)
                            trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "IntermediateSortCustomAction", errMessage);
                    }

                    if (TPend != null)
                    {
                        sorterEnd = TPend.TPSorterEnd;
                        ComponentIndexEnd = TPend.ComponentIndexEnd;

                        // if it was an Individual Bag Sort
                        if (item.idIndividualBagSort.HasValue)
                        {
                            bCompletedIndividualSort = _sortingDAL.CompleteIndividualBaggageSort(item, bFinalTarget, TPid.Value, out IndSortSpecialSort, out errMessage);
                            if (string.IsNullOrEmpty(errMessage))
                                trackEvents.WriteMessage(TrackingLevel.Info, "IndividualBagSortComplete", item.ID);
                            else if (errMessage != "NOINDIVIDUAL")
                                trackEvents.WriteMessage(TrackingLevel.Info, "IndividualBagSortCompleteError", item.ID, errMessage);
                        }
                    }
                    string eventType = string.Empty;
                    eventType = TPid.HasValue ?
                        _sortedEventTypes.GetEventTypeByTP(sortResult.Type, TPid.Value) :
                        _sortedEventTypes.GetEventType(sortResult.Type, ComponentIndexEnd);

                    object oEvType = null;
                    BagTrackingEventType? evType = null;
                    if (eventType != null)
                    {
                        Enum.TryParse(typeof(BagTrackingEventType), eventType, out oEvType);
                        evType = (BagTrackingEventType)(oEvType);
                    }


                    List<GW.ItemDestination> activeDests = _trackingDAL.GetActiveItemDestinations(item, "FINAL", out errMessage);
                    activeDests.AddRange(_trackingDAL.GetActiveItemDestinations(item, "SPECIAL", out errMessage));

                    if (bCompletedIndividualSort)
                    {
                        SortingReason = FinalSortReasonEnum.Individual;
                        SortingReasonSpecial = IndSortSpecialSort;
                        ir.SpecialSort = IndSortSpecialSort;
                    }
                    else
                    {
                        GW.ItemDestination activeDest = activeDests.Where(ad => ad.IDDestination == ComponentIndexEnd).FirstOrDefault();

                        if (activeDest == null && c != null)
                        {
                            List<GW.Component> compByMaster = _trackingDAL.GetComponentsByMaster(c.IDPLCNode ?? 0, ComponentIndexEnd, out errMessage);
                            activeDest = activeDests.Where(ad => compByMaster.Select(c => c.ComponentIndex).ToList().Contains(ad.IDDestination)).FirstOrDefault();
                        }

                        SortingReason = activeDest?.SortingReason ?? SortingReason;
                        SortingReasonSpecial = activeDest?.SortingReasonSpecial ?? SortingReasonSpecial;
                        ir.SpecialSort = SortingReasonSpecial;

                        if (!string.IsNullOrEmpty(SortingReasonSpecial))
                        {
                            bool bRet3 = _trackingDAL.SetItemSpecialSort(item, SortingReasonSpecial, out errMessage);
                            if (!bRet3)
                            {
                                trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemSpecialSort", errMessage);
                            }
                        }

                        if (SortingReasonSpecial == "LATE")
                        {
                            trackEvents.WriteMessage(TrackingLevel.Info, "CreateBPM", "LATE", item.ID, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.CheckPoint, sortResult.Destination, DateTime.Now.ToString("o", CultureInfo.InvariantCulture));
                            bRet = _BPM.CreateBPMForComponent("LATE", item, sortResult.Envelope.SourceNode, sortResult.CheckPoint, sortResult.CheckPoint, sortResult.Destination, null, DateTime.Now, out BPMPending, out errMessage);
                            trackEvents.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, "LATE", errMessage);
                        }
                    }

                    //GW.ItemDestination activeDest = activeDests.Where(ad => ad.IDDestination == ComponentIndexEnd).FirstOrDefault();
                    if (evType.HasValue)
                        _trackingDAL.AddBagTracking(_clock.Now, evType.Value, item.BagId,
                            item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, sortResult.Cell, ComponentIndexEnd, TPid == null ? 0 : TPid.Value, string.Empty, ir.IDReason, SortingReason, SortingReasonSpecial,
                            out errMessage, eventType, ComponentIndexEnd);

                    // start sorter if needed
                    if (TPend != null)
                    {
                        trackEvents.WriteMessage(TrackingLevel.Info, "GENERIC", $"TPEnd: {TPend} {TPend.DestinationArea} {TPend.StartSorterIfNeeded} {sortResult.Type}");

                        // Verify if we must start the sorter towards the line goes to
                        if (TPend.DestinationArea.HasValue && TPend.StartSorterIfNeeded && sortResult.Type == 1)
                        {
                            SorterStatus sorterStatus = _sortingDAL.GetSorterStatus(TPend.DestinationArea.Value, out errMessage);
                            if (sorterStatus == null)
                                trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "GetSorterStatus", $"{TPend.DestinationArea.Value} err:{errMessage}");
                            else
                            {
                                trackEvents.WriteMessage(TrackingLevel.Info, "GENERIC", $"SorterStatus: {sorterStatus.ID} is StandBy:{sorterStatus.Standby}");

                                TimeSpan ts = DateTime.Now - (sorterStatus.LastCommandSentTS ?? DateTime.MinValue);

                                trackEvents.WriteMessage(TrackingLevel.Info, "GENERIC", $"SorterStatus: {sorterStatus.ID} minutes from last command {ts.TotalMinutes}");

                                if (sorterStatus.Standby && ts.TotalMinutes > 1)
                                {
                                    startSorterCommand = new SorterStart();
                                    startSorterCommand.SorterPLCID = (short)sorterStatus.ID;
                                    startSorterCommand.Start = true;
                                    sorterStatus.LastCommandSentTS = DateTime.Now;
                                    bRet2 = _sortingDAL.UpdateSorterStatus(new List<SorterStatus>() { sorterStatus }, out errMessage);
                                    if (!bRet2)
                                        WLog(Severity.Error, "EX_MESSAGE", "UpdateSorterStatus", errMessage);
                                    else
                                        trackEvents.WriteMessage(TrackingLevel.Info, "GENERIC", $"Sent StartSorter Command to Agent: {sorterStatus.ID}");
                                }
                                else if (sorterStatus.Started)
                                {
                                    // reset edge baggageCounter timeout
                                    bool bRet1 = _sortingDAL.ResetSorterEdgeStandbyTimer(sorterStatus.IDComponent, out errMessage);
                                    if (!bRet1)
                                        WLog(Severity.Error, "EX_MESSAGE", "ResetSorterEdgeStandbyTimer", errMessage);
                                    else
                                        trackEvents.WriteMessage(TrackingLevel.Info, "GENERIC", $"Updated FM Edge BagCounterTS for Component {sorterStatus.IDComponent}");
                                }
                            }
                        }
                    }
                }
                else
                {
                    BagTrackingEventType? evType = null;

                    string eventType = string.Empty;
                    eventType = TPid.HasValue ?
                        _sortedEventTypes.GetEventTypeByTP(sortResult.Type, TPid.Value) :
                        _sortedEventTypes.GetEventType(sortResult.Type, ComponentIndexEnd);

                    if (eventType != null)
                    {
                        object oEvType = null;
                        Enum.TryParse(typeof(BagTrackingEventType), eventType, out oEvType);
                        evType = (BagTrackingEventType)(oEvType);

                        if (evType.HasValue)
                            _trackingDAL.AddBagTracking(_clock.Now, evType.Value, item.BagId,
                            item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, sortResult.Cell, ComponentIndexEnd, TPid.Value, string.Empty, ir.IDReason, SortingReason, SortingReasonSpecial,
                            out errMessage, eventType, ComponentIndexEnd);
                    }
                }
            }
            else
            {
                // in case of other sort result types (3/Not Sorted, 4/Tracking Lost)

                if (sortResult.Result == 4) // TrackingLost
                {
                    trackEvents.WriteMessage(TrackingLevel.Info, "TrackingLost");
                    if (compIndex != -1)
                    {
                        if (c != null)
                            TPName = c.Name;
                        if (string.IsNullOrEmpty(TPName))
                            TPName = _trackingDAL.GetTrackingLostLocationName(compIndex, out errMessage);

                        GW.TrackingPointsConfiguration tpc = null;
                        if (TPid != null)
                            tpc = _trackingDAL.GetTrackingPointConfiguration((int)TPid, out errMessage);
                        if ((tpc?.bTrackEvent) ?? true)//if (tpc == null || (bool)tpc.bTrackEvent)
                            _trackingDAL.AddBagTracking(_clock.Now, BagTrackingEventType.TrackingLost, item.BagId,
                                                        item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, sortResult.Cell, (string)null, TPid, string.Empty, ir.IDReason, out errMessage, "TrackingLost", sortResult.Result, sortResult.Reason, TPName);
                    }
                }
                else if (sortResult.Result == 3) // not sorted)
                {
                    _trackingDAL.AddBagTracking(_clock.Now, BagTrackingEventType.NotSorted, item.BagId,
                    item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, sortResult.Cell, sortResult.Destination, TPid, string.Empty, ir.IDReason, out errMessage, "NotSorted", sortResult.Result, sortResult.Reason);

                    trackEvents.WriteMessage(TrackingLevel.Info, "ItemNotSorted");
                }
                else if (sortResult.Result == 5) // appeared)
                {
                    _trackingDAL.AddBagTracking(_clock.Now, BagTrackingEventType.Appeared, item.BagId,
                    item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, sortResult.Cell, compIndex, TPid, string.Empty, ir.IDReason, out errMessage, "Appeared", sortResult.Result, sortResult.Reason);

                    trackEvents.WriteMessage(TrackingLevel.Info, "Appeared");
                }

            }

            bool breturn = _trackingDAL.SetItemResult(ir, out errMessage);
            if (!breturn)
                trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemResult", errMessage);

            if (TPend?.TrackLostOnSorted ?? false)
            {
                bool bret2 = _trackingDAL.SetPieceLost(item, out errMessage);
                if (bret2)
                    trackEvents.WriteMessage(TrackingLevel.Info, "ForceTrackingLost", item.ID);
                else
                    trackEvents.WriteMessage(TrackingLevel.Error, "ForceTrackingLostError", item.ID, errMessage);
            }

            trackEvents.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After SetItemResult");

            return breturn;
        }

        public virtual bool FinalSortCustomAction(GW.Item item, LAP.SortResultMsg sortResult, DataAccess.GW.ITrackingEvents trackEvents, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public virtual bool IntermediateSortCustomAction(GW.Item item, LAP.SortResultMsg sortResult, DataAccess.GW.ITrackingEvents trackEvents, SortingEdgeData TP, ref string SortReason, ref string SortReasonSpecial, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public virtual Tuple<int, int> VIDPartsfromBagId(string bagid)
        {
            int i1, i2;

            i1 = DateTime.Now.Year * 1000000 + DateTime.Now.Month * 10000 + DateTime.Now.Day * 100 + int.Parse(bagid.Substring(0, 1));
            i2 = int.Parse(bagid.Substring(1, 9));

            Tuple<int, int> t = new Tuple<int, int>(i1, i2);
            return t;
        }

        public virtual long VIDfromBagId(string bagid)
        {
            string sVID = string.Empty;

            sVID = DateTime.Now.ToString("yyyyMMdd") + "0" + bagid.Substring(0, 10);

            return long.Parse(sVID);
        }

        public virtual bool SetTrackingEvent(GW.Item item, LAP.TrackingEventNotificationMsg trackingEvent, DataAccess.GW.ITrackingEvents trackEvents, out bool bFinalTarget, out TrackingPoint TP, out string errMessage)
        {
            errMessage = string.Empty;
            bFinalTarget = false;
            trackEvents.SetItem(item);
            string TPName = trackingEvent.toString();
            int? TPid = null;

            GW.ItemResult ir = new GW.ItemResult(trackingEvent);
            ir.IDItem = item.ID;

            string sorterEnd = string.Empty;

            TP = _trackingDAL.GetTPbyID(trackingEvent.TrackingPoint);
            TP ??= _trackingDAL.GetTPfromComponent(trackingEvent.TrackingPoint, trackingEvent.Envelope.SourceNode);

            TPid = TP?.TPID ?? trackingEvent.TrackingPoint;

            if (TP != null)
            {
                sorterEnd = TP?.TPSorter ?? string.Empty;
                TPName = !string.IsNullOrEmpty(TP.TPDescription) ? TP.TPDescription :
                    !string.IsNullOrEmpty(TP.TPName) ? TP.TPName :
                    TP.TPID.ToString();
            }

            bool breturn = _trackingDAL.SetItemResult(ir, out errMessage);
            if (!breturn)
                trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemResult", errMessage);
            trackEvents.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After SetItemResult");

            if (trackingEvent.TrackingEvent == 2) // Contaminated)
            {
                trackEvents.WriteMessage(TrackingLevel.Info, "Contaminated");
            }
            else if (trackingEvent.TrackingEvent == 4) // TrackingLost
            {
                trackEvents.WriteMessage(TrackingLevel.Info, "TrackingLost");

                _trackingDAL.AddBagTracking(BagTrackingEventType.TrackingLost, item.BagId,
                                                item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, item.CellId, trackingEvent.TrackingPoint.ToString(), TPid, string.Empty, 400 + trackingEvent.Reason, out errMessage, "TrackingLost", trackingEvent.TrackingEvent, trackingEvent.Reason, TPName);
            }
            else if (trackingEvent.TrackingEvent == 5) // Appeared)
            {
                _trackingDAL.AddBagTracking(BagTrackingEventType.Appeared, item.BagId,
                item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, item.CellId, trackingEvent.TrackingPoint.ToString(), TPid, string.Empty, 500 + trackingEvent.Reason, out errMessage, "Appeared", trackingEvent.TrackingEvent, trackingEvent.Reason);

                trackEvents.WriteMessage(TrackingLevel.Info, "Appeared");
            }

            return breturn;
        }

        protected List<TargetSortData> GetTargetsToSorter(List<SortingEdgeData> edges)
        {
            return edges.Where(e => e.AreaType == "Sorter").OrderBy(t => t.EdgeWeight).Select(e => new TargetSortData() { Target = e.ComponentIndexEnd, TargetTPid = e.EdgeEndID }).ToList();
        }

        public bool InsertScannerStat(GW.Item item, LAP.BaseMessage scStat, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            SortingEdgeData TP = null;
            SortingEdgeData TPOut = null;

            try
            {
                List<SortingEdgeData> edges = GetSortingEdges(item, scStat, tracker, ref TP, out TPOut, out errMessage);

                switch (((LAP.ScannerStatMsg)scStat).ReadStatus)
                {
                    case 1: SetScannerStats(item, TP, 0, 0, 0, false, false); break;
                    case 2: SetScannerStats(item, TP, 2, 2, 2, true, false); break;
                }

                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool InsertSecurityReq(GW.Item item, LAP.SecurityReqMsg secReq, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                if (secReq.HiRisk)
                    _trackingDAL.AddBagTracking(item, BagTrackingEventType.SecurityReq, (string)null, null, null, "SEL", out errMessage, "SecurityReq", "SEL");
                else
                    _trackingDAL.AddBagTracking(item, BagTrackingEventType.SecurityReq, (string)null, null, null, "", out errMessage, "SecurityReq", "Auto");


                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public virtual void ManageItemData(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, out SortingEdgeData TPOut, out string errMessage)
        {
            errMessage = string.Empty;
            TPOut = null;
            SortingEdgeData TP = null;
            string bagid = string.Empty;
            string flight = string.Empty;
            DateTime? SDT = null;
            List<BSMData> BSMs = null;
            var bResetSecurityOnFlightChange = (bool)(GlobalConf["RESET_SECURITY_ON_FLIGHT_CHANGE"] ?? false);

            LAP.ItemDataMsg itemData = (LAP.ItemDataMsg)destReq;

            if (itemData.Barcodes.Any())
                bagid = itemData.Barcodes[0];

            List<SortingEdgeData> edges = GetSortingEdges(item, destReq, tracker, ref TP, out TPOut, out errMessage);
            if (destReq.Barcodes != null && destReq.Barcodes.Any())
            {
                BSMs = _sortingDAL.FindBSM(destReq.Barcodes, out errMessage);

                if (BSMs.Count == 1)
                {
                    flight = BSMs[0].Flight;
                    SDT = BSMs[0].SDT;

                    try
                    {
                        if (item.Flight != BSMs[0].Flight && bResetSecurityOnFlightChange)
                            _trackingDAL.ResetItemSecurity(item, out errMessage);

                        _trackingDAL.SetBagId(item, BSMs[0].ID, BSMs[0].BagId, BSMs[0].SDT, out errMessage);
                        _trackingDAL.SetFlight(item, BSMs[0].BaggageType, BSMs[0].SDT, BSMs[0].Flight, out errMessage);
                    }
                    catch { }
                }
            }

            if (edges.Count > 0)
                TP = edges[0];

            if (TP != null && TP.idATRTP != 0)
            {
                _trackingDAL.AddBagTracking(BagTrackingEventType.Scanner,
                                            bagid,
                                            item.LastVid,
                                            item.ControlId,
                                            item.Flight,
                                            item.SDT,
                                            TP.TPSorterStart,
                                            item.CellId,
                                            string.Empty,
                                            TP.EdgeStartID,
                                            string.Empty,
                                            out errMessage, "Scanner", TP.EdgeStartName, string.Join(",", destReq.Barcodes));

                SetScannerStats(item, TP, destReq.Barcodes.Count, destReq.Barcodes.Count, BSMs.Count(),
                            ((LAP.ItemDataMsg)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 1) == 1).Any(),
                            ((LAP.ItemDataMsg)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 2) == 2).Any());
            }
        }

        /// <summary>
        /// This function must return true if we do not want to send destinations to the plc when OCR is triggered.
        /// Else, must return false when we want to provide destinations to the plc even when triggering the OCR.
        /// </summary>
        /// <returns>true or false</returns>
        public virtual bool ReturnOCR()
        {
            return true;
        }

        public virtual List<int> CalculateOCRDestinations(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, out string IATAcode, out string ControlId, out SortingEdgeData TPOut, out string errMessage)
        {
            return CalculateDestinations(Caller, item, destReq, tracker, out IATAcode, out ControlId, out TPOut, out errMessage);
        }

        public virtual List<int> CalculateDestinations(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, out string IATAcode, out string ControlId, out SortingEdgeData TPOut, out string errMessage)
        {
            bool bOCR = false;
            bool bMES = false;
            errMessage = string.Empty;
            List<TargetSortData> FinalTargets = new List<TargetSortData>();
            SortingEdgeData TP = null;
            IATAcode = string.Empty;
            List<TargetSortData> SelectedTargets = new List<TargetSortData>();
            List<TargetSortData> EBSTargets = new List<TargetSortData>();
            ControlId = string.Empty;
            GlobalConf.Refresh();
            bool GenControlId = (bool)(GlobalConf["GEN_CONTROLID"] ?? false);
            int ocrProcQueueId = 0;
            bool bEmptySortInstructions = false;
            GW.FlightData Flight = null;
            BSMData BSM = null;

            IATAcode = item.BagId;
            ControlId = item.ControlId;

            List<SortingEdgeData> edges = GetSortingEdges(item, destReq, tracker, ref TP, out TPOut, out errMessage);

            if (TPOut != null && TPOut.bulkyNormal == "B")
            {
                bool bRet = _sortingDAL.SetItemBulky(item, out errMessage);
                if (!bRet)
                    tracker.WriteMessage(TrackingLevel.Error, "errSettingItemBulky", item.ID, errMessage);
            }

            if (edges.Count == 1 && edges[0].idATRTP == 0 && edges[0].ComponentTypeStart != "MES")
            {
                return new List<int>() { edges[0].ComponentIndexEnd };
            }

            if (destReq is LAP.DestinationRequestMsg && ((LAP.DestinationRequestMsg)destReq).DataType == LAP.BufferDataType.BUFF_NODATA_ATROFF && item.itemBarcodes.Count == 0)
                bEmptySortInstructions = true;

            if (edges.Count == 0)
                bEmptySortInstructions = true;

            if (TP?.IgnoreNoRead ?? false)
                bEmptySortInstructions = true;

            if (bEmptySortInstructions)
            {
                #region KID
                if (GenControlId)
                {
                    if (!string.IsNullOrEmpty(item.ControlId))
                        ControlId = item.ControlId;
                    else
                    {
                        ControlId = GenerateControlID(item, null, out errMessage);
                        if (string.IsNullOrEmpty(ControlId))
                        {
                            tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                        }
                    }

                }
                #endregion

                return new List<int>() { 0 };
            }

            if (!TP.MasterTP.HasValue)
            {
                if ((item.LastTP ?? 0) != TP.EdgeStartID)
                {
                    item.LastTP = TP.EdgeStartID;
                    _trackingDAL.SetItemLastTP(item, item.LastTP.Value, out errMessage);
                    item.RecirculationCounter = 0;
                }
                
                item.RecirculationCounter++;

                _trackingDAL.SetItemRecirculation(item, item.RecirculationCounter, out errMessage);
            }

            tracker.AddTimingStep("SortingEdges");
            try
            {
                if (!item.bCheckOSS && (TP.CheckOSS ?? false))
                {
                    _trackingDAL.SetItemOSS(item, out errMessage);
                }

                HBSTrackingLostReset(item, TP, tracker, out errMessage);

                bool bRets = ManageSpecialModeFinalTargets(Caller, item, destReq, tracker, ref TP, ref FinalTargets, ref SelectedTargets, ref EBSTargets, ref edges, out Flight, out BSM,
                                                   out IATAcode, out ControlId, out bMES, out bOCR, out ocrProcQueueId, out errMessage);

                if (!bRets)
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialModeFinalTargets", errMessage);

                // if item has non overridable final or special destinations set
                // copy data in FinalTargets list
                if (item.ItemDestinations.Where(id => id.bActive && !id.bOverridable && (id.DestinationType == "FINAL" || id.DestinationType == "SPECIAL")).Any())
                {
                    foreach (GW.ItemDestination id in item.ItemDestinations.Where(id => id.bActive && !id.bOverridable))
                    {
                        TargetSortData targetSort = new TargetSortData();
                        targetSort.Sorter = id.Sorter;
                        targetSort.Target = id.IDDestination;
                        targetSort.TargetType = id.DestinationType;
                        targetSort.Weight = id.OrderIndex;
                        targetSort.bOverridable = false;
                        targetSort.SortingReason = id.SortingReason;
                        targetSort.SortingReasonSpecial = id.SortingReasonSpecial;
                        FinalTargets.Add(targetSort);
                    }
                    tracker.WriteMessage(TrackingLevel.Info, "NonOverridableFinalSorts", string.Join(",", FinalTargets.Select(ft => ft.Target)));
                }
                tracker.AddTimingStep("NonOverridableFinalSorts");

                // if we have EBS, and we find a bag at a DP, then it means the bag is not in the accumulation line anymore
                // so it's removed from there
                if (bEBS)
                {
                    int iRet = _EBSDAL.BagRemovedFromEBS(item, out errMessage);
                    tracker.AddTimingStep("BagRemovedFromEBS");

                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "ErrRemoveBagEBS", item.ID, iRet, errMessage);
                    else if (iRet > 0)
                        tracker.WriteMessage(TrackingLevel.Info, "RemoveBagEBS", item.ID, iRet);
                }

                // if there are no FinalTargets set, enters the part where FinalTarget calculation is done
                if (FinalTargets.Where(ft => !ft.bOverridable).Count() == 0 && !SelectedTargets.Any())
                {
                    if (TPOut.bulkyNormal == "B")
                    {

                        if (!CalculateFinalTargetsBulky(Caller, item, destReq, tracker, ref TP, ref FinalTargets, ref SelectedTargets, ref EBSTargets, ref edges, out Flight, out BSM,
                        out IATAcode, out ControlId, out bMES, out bOCR, out ocrProcQueueId, out errMessage))
                            return new List<int>() { 0 };
                    }
                    else if (!CalculateFinalTargets(Caller, item, destReq, tracker, ref TP, ref FinalTargets, ref SelectedTargets, ref EBSTargets, ref edges, out Flight, out BSM,
                                                    out IATAcode, out ControlId, out bMES, out bOCR, out ocrProcQueueId, out errMessage))
                    {
                        // this method would return either:
                        // EBS targets - in case the bag is early and we have EBS
                        // Final targets - indicating which final target must be reached by the bag
                        // Selected targets - intermediate targets to take in order to reach some special area such as MES, OCR or XRays
                        #region KID
                        if (GenControlId)
                        {
                            if (!string.IsNullOrEmpty(item.ControlId))
                                ControlId = item.ControlId;
                            else
                            {
                                ControlId = GenerateControlID(item, null, out errMessage);
                                if (string.IsNullOrEmpty(ControlId))
                                {
                                    tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                                }
                            }

                        }
                        #endregion

                        // if the method return false there are two possibilities:
                        // the bag has been sent to OCR 
                        // the bag must remain on sorter - maybe to wait another result

                        if (!bOCR)
                            return new List<int>() { 0 };
                        if (ReturnOCR())
                            return new List<int>() { ocrProcQueueId };
                    }

                    tracker.AddTimingStep("After CalcFinalTargets");
                }

                CheckForPendingBPMs(item, tracker);
                tracker.AddTimingStep("After CheckPendingBPM");

                tracker.WriteMessage(TrackingLevel.Info, "Targets", "AfterCalcFinalTargets",
                    string.Join(",", FinalTargets.Select(ft => ft.Target)),
                    string.Join(",", EBSTargets.Select(ft => ft.Target)),
                    string.Join(",", SelectedTargets.Select(ft => ft.Target))
                    );

                // if the item has a clean security status
                // check if the security status is expired (more than N minutes after receiving clean status)
                // if it's the case the security status of the item is reset
                if (item.bSecurityStatus)
                {
                    int HBSExpireTime = (int)(GlobalConf["HBS_EXPIRE_TIME"] ?? 0);
                    if (HBSExpireTime > 0)
                    {
                        ItemSecurity sec = _trackingDAL.GetItemLastSecurity(item, out errMessage);

                        if (sec != null)
                        {
                            TimeSpan ts = DateTime.Now - sec.timestamp;
                            if (ts.TotalMinutes > HBSExpireTime)
                            {
                                bool bRet2 = _trackingDAL.ResetItemSecurity(item, out errMessage);
                                tracker.AddTimingStep("Reset Item Security");

                                if (!bRet2)
                                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", errMessage);
                                item.bSecurityStatus = false;
                                item.LastSecurityStatusFromPLC = null;
                                tracker.WriteMessage(TrackingLevel.Info, "HBSExpire");
                            }
                        }
                        else
                        {
                            bool bRet2 = _trackingDAL.ResetItemSecurity(item, out errMessage);
                            tracker.AddTimingStep("Reset Item Security");

                            if (!bRet2)
                                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", errMessage);
                            item.bSecurityStatus = false;
                            item.LastSecurityStatusFromPLC = null;
                            tracker.WriteMessage(TrackingLevel.Info, "HBSExpire");
                        }
                    }
                }

                // if:
                // - we need to check security on this tracking point
                // - item is not clean
                // - and there is priority to security check over MES/OCR or the item is not to be sent to MES or OCR
                // - and item is not OSS
                if (TP.CheckSecurity
                    && !item.bSecurityStatus
                    && (item.MinimumScreeningLevel ?? (int)ScreeningLevelRequired.Auto) != (int)ScreeningLevelRequired.OSS
                    && (TP.PrioSecurity || (!bMES && !bOCR))
                    && (FinalTargets.Any() || SelectedTargets.Any() || EBSTargets.Any()
                    || item.idBSM.HasValue))
                {
                    //EBSTargets.Clear(); // this is because in case of no sorting is needed (item must wait on presorter) we cannot send the item to EBS
                    bool L4Fault = (bool)(GlobalConf["LEVEL4_FAULT"] ?? false);
                    tracker.WriteMessage(TrackingLevel.Info, "CheckItemSecurityTargets", TP.EdgeStartID, TP.CheckSecurity, item.bSecurityStatus, item.LastSecurityStatusFromPLC, L4Fault);
                    bool bRet = GetSecurityTargets(TP, tracker, item, destReq, Flight, BSM, ref SelectedTargets, ref FinalTargets, ref EBSTargets, out errMessage);
                }
                tracker.AddTimingStep("CheckItemSecurityTargets");

                tracker.WriteMessage(TrackingLevel.Info, "Targets", "AfterGetSecurityTargets",
                                                                    string.Join(",", FinalTargets.Select(ft => ft.Target)),
                                                                    string.Join(",", EBSTargets.Select(ft => ft.Target)),
                                                                    string.Join(",", SelectedTargets.Select(ft => ft.Target))
                                                                    );

                if (FinalTargets?.Count() > 0)
                {
                    //tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', FinalTargets.Select(t => t.ToString()).ToList()), "CalculateFinalTargets");
                    List<int> finalTargetsInt = FinalTargets.Select(ft => ft.Target).ToList();

                    // transform 
                    FinalTargets = _sortingDAL.TransformEmergencyPlan(FinalTargets, tracker, out errMessage);

                    List<int> diffList = finalTargetsInt.Intersect(FinalTargets.Select(ft => ft.Target)).ToList();
                    if (diffList.Count() != FinalTargets.Count() || diffList.Count() != finalTargetsInt.Count())
                    {
                        //tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', FinalTargets.Select(t => t.ToString()).ToList()), "TransformEmergencyPlan");
                        finalTargetsInt = FinalTargets.Select(ft => ft.Target).ToList();
                    }
                    tracker.AddTimingStep("TransformEmergencyPlan");

                    tracker.WriteMessage(TrackingLevel.Info, "Targets", "AfterEmergencyPlan",
                                                    string.Join(",", FinalTargets.Select(ft => ft.Target)),
                                                    string.Join(",", EBSTargets.Select(ft => ft.Target)),
                                                    string.Join(",", SelectedTargets.Select(ft => ft.Target))
                                                    );

                    //// check for recirculations
                    FinalTargets = ManageRecirculations(TP, item, FinalTargets, tracker, out errMessage);

                    diffList = finalTargetsInt.Intersect(FinalTargets.Select(ft => ft.Target)).ToList();
                    if (diffList.Count() != FinalTargets.Count() || diffList.Count() != finalTargetsInt.Count())
                    {
                        //tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', FinalTargets.Select(t => t.ToString()).ToList()), "ManageRecirculations");
                        finalTargetsInt = FinalTargets.Select(ft => ft.Target).ToList();
                    }
                    tracker.AddTimingStep("ManageRecirculations");

                    ManageItemDestinations(item, FinalTargets, "FINAL");
                    ManageItemDestinations(item, FinalTargets, "SPECIAL");

                    tracker.WriteMessage(TrackingLevel.Info, "Targets", "AfterManageRecirculations",
                                                                        string.Join(",", FinalTargets.Select(ft => ft.Target)),
                                                                        string.Join(",", EBSTargets.Select(ft => ft.Target)),
                                                                        string.Join(",", SelectedTargets.Select(ft => ft.Target))
                                                                        );
                }

                if (EBSTargets?.Count() > 0)
                {
                    ManageItemDestinations(item, EBSTargets, "EBS");
                    _trackingDAL.DisableItemDestinations(item, "FINAL", out errMessage);
                    _trackingDAL.DisableItemDestinations(item, "SPECIAL", out errMessage);
                }

                if (FinalTargets.Any())
                {
                    bool bFinalCheck = CheckFinalTargetsSorterStatus(TP, ref FinalTargets, tracker, out errMessage);
                    if (!bFinalCheck)
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "CheckFinalTargetsSorterStatus", errMessage);

                    if (item.FinalTarget != FinalTargets[0].Target)
                    {
                        bool bRet3 = _trackingDAL.SetItemFinalTarget(item, FinalTargets[0].Target, out errMessage);
                        if (!bRet3)
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemFinalTarget", errMessage);
                    }

                    List<int> removedTargets = _sortingDAL.FilterNotReachableTargets(TP, FinalTargets, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "FilterNotReachableTargets", errMessage);

                    if (removedTargets.Any())
                    {
                        tracker.WriteMessage(TrackingLevel.Info, "FilteredTargets", string.Join(",", removedTargets));
                        List<TargetSortData> TargetsToRemove = FinalTargets.Where(ft => removedTargets.Contains(ft.Target)).ToList();
                        foreach (TargetSortData tsd in TargetsToRemove)
                            FinalTargets.Remove(tsd);
                    }

                    SpecialFilterFinalTargets(item, TP, ref FinalTargets, tracker, out errMessage);
                }

                tracker.AddTimingStep("ManageItemDestinations");

                if (SelectedTargets?.Any() != true)
                {
                    switch (TP.Context)
                    {
                        case "F":
                            // in case we are on a Final sort DP and Final targets are present
                            // either Final production targets or special targets
                            if (FinalTargets?.Count() > 0)
                            {
                                SelectedTargets = FinalTargets
                                    .Where(ft => edges.Select(e => e.ComponentIndexEnd).Contains(ft.Target))
                                    .ToList();

                                // targets are in another sorter, get the bypass targets for the other sorter
                                if (SelectedTargets?.Count() == 0)
                                    SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, FinalTargets, out errMessage);

                                if (SelectedTargets?.Count() == 0)
                                    SelectedTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, Flight?.HDL, Flight?.FLC, SpecialSortsEnum.NotOk, out errMessage);
                            }
                            else if (EBSTargets?.Count() > 0)
                            {
                                SelectedTargets = EBSTargets
                                    .Where(ft => edges.Select(e => e.ComponentIndexEnd).Contains(ft.Target))
                                    .ToList();

                                if (SelectedTargets?.Count() == 0)
                                    SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, EBSTargets, out errMessage);
                            }

                            tracker.AddTimingStep("Final TP - calc selected targets");
                            break;

                        case "I":
                            // in case we are on a Intermediate sort DP and Final targets are present
                            // either Final production targets or special targets

                            if (FinalTargets?.Count() > 0)
                            {
                                tracker.WriteMessage(TrackingLevel.Info, "Targets", "Before GetIntermediateTargets",
                                                    string.Join(",", FinalTargets.Select(ft => ft.Target)),
                                                    string.Join(",", EBSTargets.Select(ft => ft.Target)),
                                                    string.Join(",", SelectedTargets.Select(ft => ft.Target))
                                                    );
                                SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, FinalTargets, out errMessage);

                                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After GetIntermediate {string.Join(",", SelectedTargets.Select(ft => ft.Target))} err:{errMessage}");

                                if (!SelectedTargets.Any())
                                {
                                    SelectedTargets = edges.Where(e => FinalTargets.Select(ft => ft.Target).ToList().Contains(e.ComponentIndexEnd))
                                        .Select(e => e.ComponentIndexEnd).Distinct()
                                        .Select(e => new TargetSortData() { Target = e, TargetType = "FINAL", bOverridable = false }).ToList();

                                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After edges.where {string.Join(",", SelectedTargets.Select(ft => ft.Target))} err:{errMessage}");
                                }
                            }
                            else if (EBSTargets?.Count() > 0)
                            {
                                SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, EBSTargets, out errMessage);
                                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After EBSTargets {string.Join(",", SelectedTargets.Select(ft => ft.Target))} err:{errMessage}");
                            }

                            tracker.AddTimingStep("Intermediate TP - calc selected targets");
                            break;

                        case "E":
                            if (EBSTargets?.Count() > 0)
                            {
                                // there are targets on EBS set, so SelectedTargets should be set using those
                                SelectedTargets = EBSTargets;
                            }
                            else if (FinalTargets?.Count() >= 0)
                            {
                                // if there is a FinalTarget set:
                                // 1. check if directly reachable from current DP -> send to that target
                                // 2. if not reachable send the bag to outjections to sorter
                                List<SortingEdgeData> finaledge = edges.Where(e => FinalTargets.Select(f => f.Target).ToList().Contains(e.ComponentIndexEnd)).ToList();

                                if (finaledge?.Count() > 0)
                                    SelectedTargets = FinalTargets;
                                else
                                {
                                    SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, FinalTargets, out errMessage);
                                    if (!SelectedTargets.Any())
                                        SelectedTargets = GetTargetsToSorter(edges);
                                }
                            }

                            tracker.AddTimingStep("EBS TP - calc selected targets");
                            break;
                    }
                }


                // no targets selected, evaluate if recirculation count exceeds 
                if (!SelectedTargets.Any() && item.bSecurityStatus)
                {
                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"no selected targets and item is secure");

                    int? SorterRecThreshold = _sortingDAL.GetSorterRecirculations(TP.EdgeStartID, out errMessage);
                    if (SorterRecThreshold.HasValue && item.RecirculationCounter > SorterRecThreshold.Value)
                    {
                        FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, Flight?.HDL, Flight?.FLC, SpecialSortsEnum.NotOk, out errMessage);
                        tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"{SorterRecThreshold.Value} {item.RecirculationCounter} {string.Join(",", SelectedTargets.Select(ft => ft.Target))}");

                        if (FinalTargets?.Count() > 0)
                        {
                            _trackingDAL.DisableItemDestinations(item, "FINAL", out errMessage);
                            ManageItemDestinations(item, FinalTargets, "SPECIAL", false);

                            SelectedTargets = FinalTargets
                                .Where(ft => edges.Select(e => e.ComponentIndexEnd).Contains(ft.Target))
                                .ToList();

                            if (SelectedTargets?.Count() == 0)
                            {
                                SelectedTargets = _sortingDAL.GetIntermediateTargets(item, TP, FinalTargets, out errMessage);
                                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After GetIntermediateItemRecirculation {string.Join(",", SelectedTargets.Select(ft => ft.Target))} err:{errMessage}");
                            }
                        }
                    }
                }

                SelectedTargets = ManageRecirculations(TP, item, SelectedTargets, tracker, out errMessage);
                tracker.AddTimingStep("ManageRecirculations on selected targets");
                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After ManageRecirc {string.Join(",", SelectedTargets.Select(ft => ft.Target))} err:{errMessage}");

                List<int> returnTargets = new List<int>();
                int snapshot = SelectedTargets.Any() ? SelectedTargets[0].IDSnapshot : -1;
                tracker.WriteMessage(TrackingLevel.Debug, "SelectedTargets", string.Join(',', SelectedTargets.Select(t => t.ToString()).ToList()), snapshot);

                if (SelectedTargets?.Count() > 0)
                {
                    returnTargets = SelectedTargets
                                .OrderBy(s => s.Weight)
                                .Select(s => s.Target)
                                .Take(TP.MultipleTargets ? Math.Min(SelectedTargets.Count(), 6) : 1)
                                .ToList();
                }
                tracker.WriteMessage(TrackingLevel.Debug, "ReturnedTargets", string.Join(',', returnTargets.ToList()));

                if (!returnTargets.Any())
                {
                    tracker.WriteMessage(TrackingLevel.Debug, "NOSORT");

                    List<int> defaultTargets = edges.Where(e => e.bDefaultTarget).OrderBy(e => e.EdgeWeight).Select(e => e.ComponentIndexEnd).ToList();

                    if (defaultTargets.Any())
                    {
                        tracker.WriteMessage(TrackingLevel.Debug, "DEFAULTTARGET", string.Join(",", defaultTargets));
                        returnTargets.AddRange(defaultTargets);
                    }
                }

                // if the DP has an ATR, the add a Bag Tracking Scan event
                //

                string TrBagId = TP.idATRTP != 0 && string.IsNullOrEmpty(item.BagId) && destReq.Barcodes.Count == 1 ?
                        destReq.Barcodes[0] :
                        item.BagId;

                BagTrackingEventType evtype = TP.idATRTP == 0 ?
                    BagTrackingEventType.SortRequest :
                    Caller == "OCR" ?
                        BagTrackingEventType.OCR :
                        BagTrackingEventType.Scanner;

                string descriptionId = TP.idATRTP != 0 ? "Scanner" : "SortRequest";

                var param = TP.idATRTP != 0 ? string.Join(",", destReq.Barcodes) : "";

                string sTargets = _trackingDAL.ConvertTargetsToString(returnTargets, out errMessage);

                _trackingDAL.AddBagTracking(evtype, TrBagId,
                item.LastVid, item.ControlId, item.Flight, item.SDT, TP.TPSorterStart, item.CellId, sTargets, TP.EdgeStartID, string.Empty, out errMessage, descriptionId, TP.EdgeStartName, param);

                if (TP.idATRTP != 0)
                    tracker.AddTimingStep("Scan BagTracking");

                #region KID
                if (GenControlId)
                {
                    if (!string.IsNullOrEmpty(item.ControlId))
                        ControlId = item.ControlId;
                    else
                    {
                        ControlId = GenerateControlID(item, null, out errMessage);
                        if (string.IsNullOrEmpty(ControlId))
                        {
                            tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                        }
                    }

                }

                tracker.AddTimingStep("CalcDestinationsEnd");

                #endregion

                if (ocrProcQueueId != 0)
                    returnTargets.Insert(0, ocrProcQueueId);

                return returnTargets;
            }
            catch (Exception ex)
            {
                List<int> returnTargets = new List<int>();
                tracker.WriteMessage(TrackingLevel.Error, "EX_MESSAGE", nameof(CalculateDestinations), ex.StackTrace);

                if (TP != null)
                {
                    List<int> defaultTargets = edges.Where(e => e.bDefaultTarget).OrderBy(e => e.EdgeWeight).Select(e => e.ComponentIndexEnd).ToList();
                    if (!defaultTargets.Any())
                        defaultTargets = new List<int> { 0 };
                    tracker.WriteMessage(TrackingLevel.Debug, "DEFAULTTARGET", string.Join(",", defaultTargets));
                    returnTargets.AddRange(defaultTargets);
                }

                #region KID
                if (GenControlId)
                {
                    if (!string.IsNullOrEmpty(item.ControlId))
                        ControlId = item.ControlId;
                    else
                    {
                        ControlId = GenerateControlID(item, null, out errMessage);
                        if (string.IsNullOrEmpty(ControlId))
                        {
                            tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                        }
                    }

                }
                #endregion

                if (!returnTargets.Any())
                {
                    tracker.WriteMessage(TrackingLevel.Debug, "DEFAULTTARGET", 0);
                    returnTargets.Add(0);
                }
                return returnTargets;
            }
        }

        public bool CheckForPendingBPMs(GW.Item item, DataAccess.GW.ITrackingEvents tracker)
        {
            string errMessage = string.Empty;

            List<ItemSecurity> itemSec = _sortingDAL.GetPendingSecurityBPM(item.ID, out errMessage);

            tracker.AddTimingStep($"CheckForPendingBPMs: GetPending {itemSec.Count}");

            foreach (ItemSecurity iSec in itemSec)
            {
                TrackingPoint tp = _trackingDAL.GetTPfromComponent(iSec.ComponentIndex.Value);

                BPMSecurityInfo bpmsecinfo = GetBPMSecurityInfo(tp, iSec, out errMessage);
                bpmsecinfo.replayPendingBPM = true;

                if (bpmsecinfo == null)
                    continue;

                bool BPMPending = false;
                tracker.WriteMessage(TrackingLevel.Info, "CreateBPM", "Security", item.ID, 0, iSec.ComponentIndex, iSec.ComponentIndex.Value, null, iSec.timestamp.ToString("o", CultureInfo.InvariantCulture));
                bool bRet = _BPM.CreateBPMForComponent("Security", item, 0, iSec.ComponentIndex, iSec.ComponentIndex.Value, null, bpmsecinfo, iSec.timestamp, out BPMPending, out errMessage);
                tracker.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, 0, errMessage);

                if (iSec.BPMPending != BPMPending)
                {
                    iSec.BPMPending = BPMPending;
                    _sortingDAL.UpdateItemSecurity(iSec, out errMessage);
                    tracker.AddTimingStep($"CheckForPendingBPMs: {iSec.ID} UpdateItemSec");
                }
            }

            return true;
        }

        public bool SetScannerStats(GW.Item item, SortingEdgeData TP, int RequestBarcodesCount, int BarcodesCount, int BSMCount, bool ScannerRead, bool RFIDRead)
        {
            string errMessage = string.Empty;
            if (TP.idATRTP == 0)
                return true;

            GW.ScannerStatistics scStat = new GW.ScannerStatistics();
            scStat.TPid = TP.idATRTP;
            scStat.IDItem = item?.ID;
            scStat.ATRRequest = true;

            scStat.BarcodeFromRFID = RFIDRead;
            scStat.BarcodeFromScanner = ScannerRead;

            scStat.MultipleBarcodeItem = BarcodesCount > 1;
            scStat.MultipleRead = BarcodesCount > 1 && RequestBarcodesCount > 1;
            scStat.MRNoBSM = BarcodesCount > 1 && BSMCount == 0;
            scStat.MRMultBSM = BarcodesCount > 1 && BSMCount > 1;
            scStat.MROneBSM = BarcodesCount > 1 && (BSMCount == 1 || BSMCount < 0);

            scStat.NoRead = BarcodesCount <= 1 && RequestBarcodesCount == 0;
            scStat.GoodRead = BarcodesCount <= 1 && RequestBarcodesCount != 0;
            scStat.GoodReadNOBSM = BarcodesCount <= 1 && RequestBarcodesCount != 0 && BSMCount == 0;

            _sortingDAL.InsertScannerStatistics(scStat, out errMessage);

            return true;
        }

        protected List<SortingEdgeData> GetSortingEdges(GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, ref SortingEdgeData TP, out SortingEdgeData TPOut, out string errMessage)
        {
            List<string> errItem = new List<string>();
            TPOut = null;
            List<SortingEdgeData> edges = new List<SortingEdgeData>();
            // Read the sorting edges relevant to the decision point
            edges = _sortingDAL.GetSortingEdges(destReq.ComponentIndex, destReq.Envelope.SourceNode, out errMessage);

            if (edges.Count > 0)
            {
                TP = edges[0];
                TPOut = TP;

                if (edges[0].SetSecurity && !string.IsNullOrEmpty(edges[0].SecurityStatus))
                {
                    string secStatus = edges[0].SecurityStatus;
                    if (edges[0].SecurityStatus.StartsWith("*"))
                    {
                        if (string.IsNullOrEmpty(item.LastSecurityStatusFromPLC))
                            secStatus = edges[0].SecurityStatus.Substring(1);
                        else
                            secStatus = string.Empty;
                    }

                    if (!string.IsNullOrEmpty(secStatus))
                    {
                        LAP.ScreeningResultExtMsg screenres = new LAP.ScreeningResultExtMsg();
                        screenres.Envelope = new LAP.MsgEnvelope();
                        screenres.Envelope.SourceNode = (short)destReq.Envelope.SourceNode;
                        screenres.Envelope.DestinationNode = 1;

                        if (destReq.Barcodes.Count == 1)
                            screenres.IataCodeString = destReq.Barcodes[0];
                        else
                            screenres.IataCodeString = String.Empty;

                        screenres.ObjIDDate = destReq.ObjIDDate;
                        screenres.ObjID = destReq.ObjID;
                        screenres.LineSeq = (short)destReq.ComponentIndex;
                        screenres.ScreeningResultDataString = secStatus;


                        GW.Item secItem = _trackingDAL.InsertTrackingItem(screenres, tracker, destReq.ComponentIndex, out errItem);
                        if (secItem != null)
                        {
                            ManageScreeningResult(secItem, screenres, tracker, out errMessage);
                            item.bSecurityStatus = secItem.bSecurityStatus;
                            item.LastSecurityStatusFromPLC = secItem.LastSecurityStatusFromPLC;
                        }
                    }
                }
            }
            else
            {
                // No edges found.. this should never happen, log an error and return Destination 0
                tracker.WriteMessage(TrackingLevel.Error, "NOEDGES_FORDP", destReq.ComponentIndex);
            }

            return edges;
        }

        /// <summary>
        /// Handles special flows for MES bag flows (NOREAD, NOBSM, MULTIPLEREAD and MULTIPLEBSM)
        /// 
        /// if it's a TP in EBS area then the the priority would be:
        /// = if TP is OCR capable, try to post to OCR
        /// = if a special sort is defined, choose to sort to that special sort and return false
        /// = if none of the above send back to sorter
        /// 
        /// in caso of a TP in any other area:
        /// = if it's configured to have Special sort priority over MES (default false) and a special sort is present sort to that special sort
        /// = if TP is OCR capable, post to OCR and return false
        /// = if MES is found sort to MES
        /// = if MES is not present try to find if a special sort for the current bag flow is defined
        /// = as fallback sort to NotOK special sort
        /// 
        /// </summary>
        /// <param name="TP"></param>
        /// <param name="SelectedTargets"></param>
        /// <param name="FinalTargets"></param>
        /// <param name="tracker"></param>
        /// <param name="destReq"></param>
        /// <param name="item"></param>
        /// <param name="edges"></param>
        /// <param name="SpecialSort">one of the NOREAD, NOBSM, MULTIPLEREAD, MULTIPLEBSM</param>
        /// <param name="FallbackSpecialSort">usually set to NotOK</param>
        /// <param name="ocrProcQueueId">output param with OCR queue id</param>
        /// <param name="errMessage">output error string, if any</param>
        /// <returns></returns>
        protected bool HandleSpecialFlow(SortingEdgeData TP,
            ref List<TargetSortData> SelectedTargets,
            ref List<TargetSortData> FinalTargets,
            DataAccess.GW.ITrackingEvents tracker,
            LAP.BaseMessage destReq,
            GW.Item item,
            List<SortingEdgeData> edges,
            string SpecialSort,
            string FallbackSpecialSort,
            out int ocrProcQueueId,
            out bool bSentOCR,
            out bool bSentMES,
            out string errMessage)
        {
            bool SpecialPriorityOverMes = (bool)(GlobalConf["SPECIAL_PRIORITY_OVER_MES"] ?? false);
            int OCRTimeout = (int)(GlobalConf["OCR_TIMEOUT"] ?? 10);
            bool? bOCRStatus = null;
            errMessage = string.Empty;
            ocrProcQueueId = 0;
            bSentMES = false;
            bSentOCR = false;
            int iSentToOCR = 0;

            if (TP.Context == "E")
            {
                if (!TP.isOCR || !bOCR)
                {
                    // no OCR, try the special sort
                    FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSort, out errMessage);
                    // send back to sorter
                    if (!FinalTargets.Any())
                        SelectedTargets = GetTargetsToSorter(edges);
                    return true;
                }

                // in case of a special sort that could be processed on MES on a TP in the EBS area:
                // - in case a special sort is defined -> sort to that target
                // - otherwise send the bag to outjections to Sorter
                if (item.idOCRProc.HasValue)
                    bOCRStatus = _sortingDAL.RefreshOCRStatus(item.idOCRProc.Value, OCRTimeout, out errMessage);
                
                if (bOCRStatus.HasValue)
                    return true;
                
                // if the current DP (TP) is a ATR with OCR capabilities, then the bag is queued to OCR
                // a negative ID of the OCR Proc queue is returned to caller in order to publish the request to OCR queue
                iSentToOCR = _sortingDAL.ManageOCR(item, TP, item.itemBarcodes.Where(ib => ib.IsValid).Select(b => b.Barcode).ToList(), destReq, out errMessage);

                if (iSentToOCR == 0)
                    return true;

                _trackingDAL.SetOCRProcessingID(item, iSentToOCR, out errMessage);
                if (!string.IsNullOrEmpty(errMessage))
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetOCRProcessingID", errMessage);

                GW.ScannerStatistics scStat = new GW.ScannerStatistics();
                scStat.TPid = TP.idATRTP;
                scStat.IDItem = item?.ID;
                scStat.SentToOCR = true;
                _sortingDAL.InsertScannerStatistics(scStat, out errMessage);
                tracker.WriteMessage(TrackingLevel.Debug, "QUEUED_OCR", iSentToOCR);
                ocrProcQueueId = -iSentToOCR;
                bSentOCR = true;
                return false;
            }
            
            //in case of bag flow the bag is sent to one of the following:
            // - if a special sort is configured -> to that sorting target
            // - if OCR is configured and the ATR has not been hit yet -> it's queued to OCR
            // - if MES is configured it's sent to MES
            // - if no OCR and no MES, it's sent to special target NOTOK

            FinalTargets = SpecialPriorityOverMes ?
                _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSort, out errMessage) :
                new List<TargetSortData>();

            if (FinalTargets.Any())
                return true;

            if (bOCR
                && item.idOCRProc.HasValue
                && (bOCRStatus = _sortingDAL.RefreshOCRStatus(item.idOCRProc.Value, OCRTimeout, out errMessage)) == null
                && errMessage != "MissingRequest")
            {
                return true;
            }

            if (bOCRStatus == true)
                return true;

            if (!bOCRStatus.HasValue)
            {
                if (TP.isOCR)
                {

                    // if the current DP (TP) is a ATR with OCR capabilities, then the bag is queued to OCR
                    // a negative ID of the OCR Proc queue is returned to caller in order to publish the request to OCR queue
                    iSentToOCR = _sortingDAL.ManageOCR(item, TP, item.itemBarcodes.Where(ib => ib.IsValid).Select(b => b.Barcode).ToList(), destReq, out errMessage);

                    if (iSentToOCR != 0)
                    {
                        _trackingDAL.SetOCRProcessingID(item, iSentToOCR, out errMessage);
                        if (!string.IsNullOrEmpty(errMessage))
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetOCRProcessingID", errMessage);

                        GW.ScannerStatistics scStat = new GW.ScannerStatistics();
                        scStat.TPid = TP.idATRTP;
                        scStat.IDItem = item?.ID;
                        scStat.SentToOCR = true;
                        _sortingDAL.InsertScannerStatistics(scStat, out errMessage);
                        tracker.WriteMessage(TrackingLevel.Info, "QUEUED_OCR", iSentToOCR);
                        ocrProcQueueId = -iSentToOCR;
                        bSentOCR = true;
                        if (ReturnOCR())
                            return false;
                    }
                }
                else
                {
                    if (TP.MasterTP.HasValue && item.itemBarcodes.Where(ib => ib.IsValid).Count() == 1 && !item.idOCRProc.HasValue)
                    {
                        List<SortingEdgeData> MasterTPEdges = _sortingDAL.GetSortingEdges(TP.MasterTP.Value, out errMessage);
                        if (MasterTPEdges.Any() && MasterTPEdges[0].isOCR)
                            return true;
                    }

                    // if we are on normal TP (not with ATR with OCR capabilities) we look for the closest TP with OCR, if any
                    SelectedTargets = _sortingDAL.GetClosestOCR(TP.EdgeStartID, out errMessage);
                    if (SelectedTargets?.Any() ?? true)
                        tracker.WriteMessage(TrackingLevel.Info, "TARGET_OCR", string.Join(',', SelectedTargets.Select(ft => ft.Target).ToList()));
                }
            }

            // if bag wasn't sent to OCR (maybe because already attempted on that ATR) and no routing is available to OCR TPs
            // the bag is sent to MES

            var BSM = item.idBSM == null ? null : _sortingDAL.GetBSM((int)item.idBSM, out errMessage);
            var FLC = BSM?.FLC;

            if (((iSentToOCR == 0) || !ReturnOCR()) && SelectedTargets?.Any() != true && bMES && CheckMESReasonEnabled(item.BagDestinationStatus, FLC))
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            if (!SelectedTargets.Any())
            {
                FinalTargets = ManageSpecialSort(TP, tracker, null, null, SpecialSort, SpecialSortsEnum.NotOk, out errMessage);
                if (!string.IsNullOrEmpty(errMessage))
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);
            }
            
            return true;
        }

        public List<TargetSortData> ManageEarly(GW.Item item, SortingEdgeData TP, DataAccess.GW.ITrackingEvents tracker, List<FinalTargetsData> targets, string HDL, string FLC, string VeryEarlySpecialSort, string EarlySpecialSort, string fallbackSpecialSort, out string errMessage)
        {
            DateTime? VETime = targets[0].VeryEarlyTS;

            if (!VETime.HasValue)
            {
                int? VEoffs = (int)GlobalConf["VERY_EARLY_TIME"];
                if (VEoffs.HasValue)
                    VETime = targets[0].openTS.AddMinutes(-VEoffs.Value);
            }

            string status = BagDestinationStatus.Early.ToString();
            string specialSort = EarlySpecialSort;

            if (VETime.HasValue && _clock.Now < VETime.Value)
            {
                status = BagDestinationStatus.VeryEarly.ToString();
                specialSort = VeryEarlySpecialSort;
            }

            _trackingDAL.SetItemBagDestinationStatus(item, status, TP.TPSorterStart, TP.EdgeStartID, item.idBSM, out errMessage);
            return ManageSpecialSort(TP, tracker, HDL, FLC, specialSort, fallbackSpecialSort, out errMessage);
        }

        public List<TargetSortData> ManageSpecialSort(SortingEdgeData TP, DataAccess.GW.ITrackingEvents tracker, string HDL, string FLC, string SpecialSort, string fallbackSpecialSort, out string errMessage)
        {
            List<TargetSortData> fintrg = new List<TargetSortData>();
            errMessage = string.Empty;

            string sorter = TP?.TPSorterStart;

            string special = SpecialSort;
            fintrg = _sortingDAL.GetSpecialSort(sorter, HDL, FLC, special, out errMessage);
            if (!fintrg.Any() && !string.IsNullOrEmpty(fallbackSpecialSort))
            {
                special = fallbackSpecialSort;
                fintrg = _sortingDAL.GetSpecialSort(sorter, HDL, FLC, special, out errMessage);
            }

            if (fintrg.Any())
            {
                tracker.WriteMessage(TrackingLevel.Info, "SpecialSort", special, string.Join(',', fintrg.Select(at => at.Target).ToList()));
                return fintrg;
            }
            
            sorter = (string)GlobalConf["DEFAULT_SPECIALSORT_SORTER"];
            if (string.IsNullOrEmpty(sorter))
                return fintrg;
            
            special = SpecialSort;
            fintrg = _sortingDAL.GetSpecialSort(sorter, HDL, FLC, special, out errMessage);
            if (!fintrg.Any() && !string.IsNullOrEmpty(fallbackSpecialSort))
            {
                special = fallbackSpecialSort;
                fintrg = _sortingDAL.GetSpecialSort(sorter, HDL, FLC, special, out errMessage);
            }

            if (fintrg.Any())
                tracker.WriteMessage(TrackingLevel.Info, "SpecialSort", special, string.Join(',', fintrg.Select(at => at.Target).ToList()));

            return fintrg;
        }

        public List<LAP.FlightClosingCmdMsg> ConvertClosingFlightsCommands(List<ClosingTarget> targets, out string errMessage)
        {
            errMessage = string.Empty;

            List<LAP.FlightClosingCmdMsg> FlightClosingCmds = new List<LAP.FlightClosingCmdMsg>();

            foreach (ClosingTarget target in targets)
            {
                LAP.FlightClosingCmdMsg lamp = new LAP.FlightClosingCmdMsg(1, target.idPLC, 1, 0, 0);

                lamp.Chute = (short)target.SortDestination;
                lamp.Status = target.Lamp;

                FlightClosingCmds.Add(lamp);
            }

            return FlightClosingCmds;
        }

        public void LogBookDestinationStatus(string logbookUser, List<OnlineSortingDestinations> destinations, string newStatus)
        {
            bool retAsync;
            using (var scope = _scopeFactory.CreateScope())
            {
                IDataSacLogBookSorting _dataSacLogBookSortingAppl = scope.ServiceProvider.GetRequiredService<IDataSacLogBookSorting>();
                foreach (OnlineSortingDestinations dest in destinations)
                {
                    var logbooksort = new LogBookSorting
                    {
                        Timestamp = _clock.Now,
                        User = logbookUser,
                        Pagename = string.Empty,
                        SortingPlanName = dest.SortplanName,
                        SortingPlanId = dest.SortplanId,
                        SortingType = LogBookSortingType.ONLINE,
                        Flight = dest.Flight,
                        Destination = dest.Target,
                        Sorter = dest.Sorter,
                        Sdt = dest.SDT
                    };

                    string ev = string.Empty;
                    switch (newStatus.ToLower())
                    {
                        case "notopen": ev = LogBookSortingEvent.SortingDestinationNotOpen; break;
                        case "open": ev = LogBookSortingEvent.SortingDestinationOpen; break;
                        case "closing": ev = LogBookSortingEvent.SortingDestinationClosing; break;
                        case "closed": ev = LogBookSortingEvent.SortingDestinationClosed; break;
                        case "closedmanual": ev = LogBookSortingEvent.SortingDestinationClosedManual; break;
                    }

                    if (newStatus == "ClosedManual")
                        newStatus = "Closed (Manual)";

                    retAsync = _dataSacLogBookSortingAppl.AddNewLogBookSortingAsync(logbooksort, LogBookSortingGroup.SortingTargetsStatusChange, ev, "DestinationStatus", dest.Flight, dest.Des ?? "*", dest.Class ?? "*", dest.SortCriteria, dest.Target, newStatus).Result;
                }
            }
        }

        public virtual bool CalculateFinalTargetsBulky(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, ref SortingEdgeData TP,
                                   ref List<TargetSortData> FinalTargets, ref List<TargetSortData> SelectedTargets,
                                   ref List<TargetSortData> EBSTargets, ref List<SortingEdgeData> edges, out GW.FlightData Flight, out BSMData BSM,
                                   out String IATAcode, out string KID, out bool bSentMES, out bool bSentOCR, out int ocrProcQueueId, out string errMessage)
        {
            return CalculateFinalTargets(Caller, item, destReq, tracker, ref TP,
                                   ref FinalTargets, ref SelectedTargets,
                                   ref EBSTargets, ref edges, out Flight, out BSM,
                                   out IATAcode, out KID, out bSentMES, out bSentOCR, out ocrProcQueueId, out errMessage);
        }

        public virtual bool ManageSpecialModeFinalTargets(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, ref SortingEdgeData TP,
                                           ref List<TargetSortData> FinalTargets, ref List<TargetSortData> SelectedTargets,
                                           ref List<TargetSortData> EBSTargets, ref List<SortingEdgeData> edges, out GW.FlightData Flight, out BSMData BSM,
                                           out String IATAcode, out string KID, out bool bSentMES, out bool bSentOCR, out int ocrProcQueueId, out string errMessage)
        {
            IATAcode = string.Empty;
            KID = string.Empty;
            bSentOCR = false;
            bSentMES = false;
            ocrProcQueueId = 0;
            errMessage = string.Empty;
            Flight = null;
            BSM = null;

            return true;
        }

        public virtual bool CalculateFinalTargets(string Caller, GW.Item item, LAP.BaseMessage destReq, DataAccess.GW.ITrackingEvents tracker, ref SortingEdgeData TP,
                                           ref List<TargetSortData> FinalTargets, ref List<TargetSortData> SelectedTargets,
                                           ref List<TargetSortData> EBSTargets, ref List<SortingEdgeData> edges, out GW.FlightData Flight, out BSMData BSM,
                                           out String IATAcode, out string KID, out bool bSentMES, out bool bSentOCR, out int ocrProcQueueId, out string errMessage)
        {
            BSM = null;
            List<FinalTargetsData> targets = null;
            Flight = null;
            errMessage = string.Empty;
            IATAcode = string.Empty;
            KID = string.Empty;
            bool bMinimumScreeningLevelChange = false;
            bool GenControlId = (bool)(GlobalConf["GEN_CONTROLID"] ?? false);
            bool RushSpecialHandling = (bool)(GlobalConf["RUSH_SPECIAL_HANDLING"] ?? false);
            bool MultiBSMPolicyOpenFlight = (bool)(GlobalConf["MULTIBSM_POLICY_OPENFLIGHT"] ?? false);
            var bResetSecurityOnFlightChange = (bool)(GlobalConf["RESET_SECURITY_ON_FLIGHT_CHANGE"] ?? false);
            var bResetSecurityOnRUSH = (bool)(GlobalConf["RESET_SECURITY_ON_RUSH"] ?? false);

            bSentMES = false;
            bSentOCR = false;
            ocrProcQueueId = 0;


            if (GenControlId && !string.IsNullOrEmpty(item.ControlId))
                KID = item.ControlId;

            // Get the barcode to be searched, getting all the item barcodes
            List<string> BarcodeToFind = item.itemBarcodes.Where(b => b.IsValid).Select(b => b.Barcode).Distinct().ToList();

            if (BarcodeToFind.Count() == 0)
            {
                //   ========================================
                //   NO READ
                //   ========================================

                #region NOREAD
                tracker.WriteMessage(TrackingLevel.Info, "NO_READ");
                //_trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.NoRead.ToString(), out errMessage);
                //_trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.NoRead.ToString(), TP.TPSorterStart, TP.EdgeStartID, null, out errMessage);

                _trackingDAL.SetItemBagStatus(item, null, null, null, null, null, null, null, 0, BagDestinationStatus.NoRead.ToString(), BagDestinationStatus.NoRead.ToString(), false, TP.TPSorterStart, TP.EdgeStartID, out errMessage);

                tracker.AddTimingStep("NOREAD - SetItemBagStatus " + errMessage);
                if (Caller == "SORTING")
                {
                    SetScannerStats(item, TP, 0, 0, 0, false, false);
                    tracker.AddTimingStep("NOREAD - ScannerStats");
                }

                bool bRet = HandleSpecialFlow(TP,
                                  ref SelectedTargets,
                                  ref FinalTargets,
                                  tracker,
                                  destReq,
                                  item,
                                  edges,
                                  SpecialSortsEnum.NoRead,
                                  SpecialSortsEnum.NotOk,
                                  out ocrProcQueueId,
                                  out bSentOCR,
                                  out bSentMES,
                                  out errMessage);

                tracker.AddTimingStep("NOREAD - HandleSpecialFlow");

                if (!string.IsNullOrEmpty(errMessage))
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "HandleSpecialFlow", errMessage);
                if (!bRet)
                    return false;   // item queued to OCR
                #endregion

                #region KID
                if (GenControlId && string.IsNullOrEmpty(KID))
                {
                    KID = GenerateControlID(item, null, out errMessage);
                    if (string.IsNullOrEmpty(KID))
                    {
                        tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                    }
                }
                #endregion
            }
            else
            {
                List<BSMData> BSMs = _sortingDAL.FindBSM(BarcodeToFind, out errMessage);

                //RP: modified as request from ZRH, but could be valid for all; if Config GET_DELETED_BSMS is set to true also deleted BSMS 
                //    are returned, then rules are:
                //  - if there are one or more valid BSMS those must be used removing the ones with bValid==0
                //    if there are only BSMs with bValid=0 then the one with most recent timestamp will be kept

                if (BSMs.Count() > 0)
                {
                    int validCount = BSMs.Where(b => b.bValid ?? false).Count();

                    if (validCount > 0)
                        BSMs = BSMs.Where(b => b.bValid ?? false).ToList();
                    else
                        BSMs = new List<BSMData>() { BSMs.OrderByDescending(b => b.Timestamp).First() };
                }

                int inboundCount = BSMs.Where(b => b.BaggageType == "I").Count();
                int outboundCount = BSMs.Count() - inboundCount;

                tracker.AddTimingStep("FindBSM");

                if (inboundCount > 0 && outboundCount != 0)
                    BSMs = BSMs.Where(b => b.BaggageType != "I").ToList();
                else if (inboundCount > 0)
                {
                    BSMData localBSM = BSMs[0];
                    List<Common.DataAccess.GW.FlightData> localFlights = _sortingDAL.FindFlightWithType(localBSM.SDT, localBSM.FLC, localBSM.FLN, localBSM.FLX, "A", out errMessage);
                    string localFlightHDL = null;
                    if (localFlights.Any())
                        localFlightHDL = localFlights[0].HDL;

                    if (localFlightHDL == "")
                        localFlightHDL = null;

                    FinalTargets = ManageSpecialSort(TP, tracker, localFlightHDL, localBSM.FLC, SpecialSortsEnum.LocalFlight, SpecialSortsEnum.NotOk, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

                    _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.LocalFlight.ToString(), TP.TPSorterStart, TP.EdgeStartID, localBSM.ID, out errMessage);
                    tracker.AddTimingStep("LOCAL - only inbound");

                }

                if (Caller == "SORTING")
                {
                    if (destReq is LAP.DestinationRequestMsgExt ext)
                    {
                        SetScannerStats(item, TP, destReq.Barcodes.Count, BarcodeToFind.Count(), BSMs.Count(),
                                    ext.BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 1) == 1).Any(),
                                    ext.BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 2) == 2).Any());
                    }
                    else if (destReq is LAP.DestinationRequestMsg ext2)
                    {
                        SetScannerStats(item, TP, destReq.Barcodes.Count, BarcodeToFind.Count(), BSMs.Count(),
                                    ext2.BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 1) == 1).Any(),
                                    ext2.BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 2) == 2).Any());
                    }

                    tracker.AddTimingStep("READ - ScannerStats");
                }

                #region SetItemIATA
                List<string> distinctBarcodesFound = BSMs.Select(b => b.BagId).Distinct().ToList();
                if (distinctBarcodesFound.Count() == 1)
                    IATAcode = distinctBarcodesFound[0];
                else if (BarcodeToFind.Count() == 1)
                    IATAcode = BarcodeToFind[0];

                // if the number of received barcodes differs from the bagid correctly associated to BSMs
                // we must change the active barcodes
                if (distinctBarcodesFound.Count() > 0 && item.itemBarcodes.Count() != distinctBarcodesFound.Count())
                    item.itemBarcodes = _trackingDAL.SetActiveItemBarcodes(item.ID, distinctBarcodesFound, destReq.ComponentIndex, destReq.VID, out errMessage);

                tracker.SetBagId(distinctBarcodesFound);
                #endregion

                if (!FinalTargets.Any())
                {
                    // in case of multiple BSM and RUSH_SPECIAL_HANDLING is set
                    // we check if out of multiple bsms one has a RUSH exception and the others flights are in Closed status... 
                    // if so, only the RUSH BSM survives

                    if (BSMs.Count > 1)
                    {
                        if (RushSpecialHandling)
                        {
                            List<BSMData> RushBSM = BSMs.Where(b => b.Exception == "RUSH").ToList();
                            if (RushBSM.Count() == 1)
                            {
                                int cntClosed = 0;

                                foreach (BSMData b2 in BSMs)
                                {
                                    if (b2.ID == RushBSM[0].ID)
                                        continue;
                                    bool bClosed = _sortingDAL.FlightIsClosed(b2.SDT, b2.Flight, out errMessage);
                                    if (bClosed)
                                        cntClosed++;
                                }

                                if (cntClosed == BSMs.Count - 1)
                                {
                                    BSMs.Clear();
                                    BSMs.Add(RushBSM[0]);
                                    tracker.WriteMessage(TrackingLevel.Info, "BSMRush", RushBSM[0].ID);
                                }
                            }
                        }

                        if (MultiBSMPolicyOpenFlight)
                        {
                            int cntClosed = 0;
                            List<BSMData> OpenBSM = new List<BSMData>();

                            foreach (BSMData b2 in BSMs)
                            {
                                bool bClosed = _sortingDAL.FlightIsClosed(b2.SDT, b2.Flight, out errMessage);
                                if (bClosed)
                                    cntClosed++;
                                else
                                    OpenBSM.Add(b2);
                            }

                            if (OpenBSM.Count() == 1)
                            {
                                BSMs.Clear();
                                BSMs.Add(OpenBSM[0]);
                                tracker.WriteMessage(TrackingLevel.Info, "MultiBSMOneFlightPolicy", OpenBSM[0].ID);
                            }
                        }
                    }
                    
                    tracker.AddTimingStep("BSM List");

                    if (BSMs.Count > 1)
                    {
                        //   ========================================
                        //   MULTIPLE BSM
                        //   ========================================

                        #region KID
                        if (GenControlId && string.IsNullOrEmpty(KID))
                        {
                            KID = GenerateControlID(item, null, out errMessage);
                            if (string.IsNullOrEmpty(KID))
                            {
                                tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                            }
                        }
                        #endregion

                        #region MULTIPLE_BSM
                        tracker.SetBagId(distinctBarcodesFound);
                        tracker.WriteMessage(TrackingLevel.Debug, "BAGID_FOUND", tracker.Ev.BagId);
                        //TODO: set bag status MULTIPLEBSM send to MES
                        tracker.WriteMessage(TrackingLevel.Info, "MULTIPLE_BSM", BSMs.Count());

                        string ItemBagId = null;
                        if (distinctBarcodesFound.Count() == 1)
                            ItemBagId = distinctBarcodesFound[0];


                        _trackingDAL.SetItemBagStatus(item, null, ItemBagId, null, null, null, null, null, 0, MesBaggagePresenceReason.MultipleBsm.ToString(),
                            MesBaggagePresenceReason.MultipleBsm.ToString(), false, TP.TPSorterStart, TP.EdgeStartID, out errMessage);

                        if (item.bSecurityStatus && item.LastSecurityStatusFromPLC == OSSClean())
                            _trackingDAL.ResetItemSecurity(item, out errMessage);

                        //_trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.MultipleBsm.ToString(), out errMessage);
                        //_trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.MultipleBsm.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSMs[0].ID, out errMessage);
                        //_trackingDAL.ResetBagId(item, out errMessage);
                        //_trackingDAL.ResetFlight(item, out errMessage);
                        tracker.AddTimingStep("MULTIBSM - SetBagStatus " + errMessage);

                        bool bRet = HandleSpecialFlow(TP,
                                          ref SelectedTargets,
                                          ref FinalTargets,
                                          tracker,
                                          destReq,
                                          item,
                                          edges,
                                          SpecialSortsEnum.MultipleBSM,
                                          SpecialSortsEnum.NotOk,
                                          out ocrProcQueueId,
                                          out bSentOCR,
                                          out bSentMES,
                                          out errMessage);
                        tracker.AddTimingStep("MULTIBSM - HandleSpecialFlow");

                        if (!string.IsNullOrEmpty(errMessage))
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "HandleSpecialFlow", errMessage);
                        if (!bRet)
                            return false;   // item queued to OCR

                        #endregion
                    }
                    else if (BSMs.Count == 0)
                    {
                        //   ========================================
                        //   NO BSM
                        //   ========================================

                        #region KID
                        if (GenControlId && string.IsNullOrEmpty(item.ControlId))
                        {
                            KID = GenerateControlID(item, null, out errMessage);
                            if (string.IsNullOrEmpty(KID))
                            {
                                tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                            }
                        }
                        #endregion

                        #region NO_BSM
                        tracker.WriteMessage(TrackingLevel.Info, "NO_BSM");
                        string bagReadStatus;
                        string specialSortToFind;
                        string ItemBagId = null;
                        if (BarcodeToFind.Count() > 1)
                        {
                            //_trackingDAL.ResetBagId(item, out errMessage);
                            //_trackingDAL.ResetFlight(item, out errMessage);
                            bagReadStatus = BagDestinationStatus.MultipleRead.ToString();
                            specialSortToFind = SpecialSortsEnum.MultipleRead;
                        }
                        else if (BarcodeToFind.Count() == 1)
                        {
                            ItemBagId = BarcodeToFind[0];
                            //_trackingDAL.SetBagId(item, null, BarcodeToFind[0], null, out errMessage);
                            //_trackingDAL.ResetFlight(item, out errMessage);
                            bagReadStatus = BagDestinationStatus.NoBsm.ToString();
                            specialSortToFind = SpecialSortsEnum.NoBSM;
                        }
                        else
                        {
                            //_trackingDAL.ResetBagId(item, out errMessage);
                            //_trackingDAL.ResetFlight(item, out errMessage);
                            bagReadStatus = BagDestinationStatus.NoRead.ToString();
                            specialSortToFind = SpecialSortsEnum.NoRead;
                        }
                        if (item.bSecurityStatus && item.LastSecurityStatusFromPLC == OSSClean())
                            _trackingDAL.ResetItemSecurity(item, out errMessage);

                        _trackingDAL.SetItemBagStatus(item, null, ItemBagId, null, null, null, null, null, 0, bagReadStatus, bagReadStatus, false, TP.TPSorterStart, TP.EdgeStartID, out errMessage);

                        //_trackingDAL.SetItemBagReadStatus(item, bagReadStatus, out errMessage);
                        //_trackingDAL.SetItemBagDestinationStatus(item, bagReadStatus, TP.TPSorterStart, TP.EdgeStartID, null, out errMessage);
                        tracker.AddTimingStep("NOBSM - SetBagStatus " + errMessage);

                        bool bRet = HandleSpecialFlow(TP,
                                          ref SelectedTargets,
                                          ref FinalTargets,
                                          tracker,
                                          destReq,
                                          item,
                                          edges,
                                          specialSortToFind,
                                          SpecialSortsEnum.NotOk,
                                          out ocrProcQueueId,
                                          out bSentOCR,
                                          out bSentMES,
                                          out errMessage);
                        tracker.AddTimingStep("NOBSM - HandleSpecialFlow");

                        if (!string.IsNullOrEmpty(errMessage))
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "HandleSpecialFlow", errMessage);
                        if (!bRet)
                            return false;   // item queued to OCR

                        #endregion
                    }
                    else if (BSMs.Count == 1)
                    {
                        #region BSM_OK
                        tracker.SetBagId(distinctBarcodesFound);
                        tracker.WriteMessage(TrackingLevel.Debug, "BAGID_FOUND", tracker.Ev.BagId);

                        tracker.WriteMessage(TrackingLevel.Info, "ONE_BSM", BSMs[0].ID);
                        // cerca il volo
                        BSM = BSMs[0];



                        if (item.bItemReconciled && item.Flight != BSMs[0].Flight && bResetSecurityOnFlightChange)
                            _trackingDAL.ResetItemSecurity(item, out errMessage);

                        if(item.bItemReconciled && !string.IsNullOrEmpty(BSMs[0].Exception) && BSMs[0].Exception.Contains("RUSH") && bResetSecurityOnRUSH)
                            _trackingDAL.ResetItemSecurity(item, out errMessage);

                        _trackingDAL.SetItemBagStatus(item, BSM.ID, BSM.BagId, BSM.BaggageType, BSM.SDT, BSM.Flight, BSM.DES, BSM.FLC, BSM.FLN, string.Empty, "BagOk", false, TP.TPSorterStart, TP.EdgeStartID, out errMessage);

                        #endregion

                        tracker.AddTimingStep("ONE BSM - SetBagStatus " + errMessage);

                        bool bRet = ManageBSMSpecialCase(item, ref TP, BSM, ref SelectedTargets, tracker, out bSentMES, out bSentOCR, out errMessage);
                        if (!bRet)
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageBSMSpecialCase", errMessage);
                        if (SelectedTargets.Any())
                            return true;

                        FinalTargets = _sortingDAL.CheckIndividualBaggageSort(TP, BSM, item, out errMessage);
                        tracker.AddTimingStep("CheckIndividualBagSort");

                        tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', FinalTargets.Select(t => t.ToString()).ToList()), "IndividualBagSort");

                        if (!string.IsNullOrEmpty(errMessage))
                            tracker.WriteMessage(TrackingLevel.Info, "IndividualBagSortError", BSM.ID);
                        else
                        {
                            tracker.WriteMessage(TrackingLevel.Info, "IndividualBagSort", BSM.ID, string.Join(",", FinalTargets.Select(ft => ft.ToString())));
                            if (FinalTargets.Any())
                                return true;
                        }

                        #region KID
                        if (GenControlId && string.IsNullOrEmpty(KID))
                        {
                            KID = GenerateControlID(item, BSMs[0].ID, out errMessage);
                            if (string.IsNullOrEmpty(KID))
                            {
                                tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                            }
                        }
                        #endregion

                        List<Common.DataAccess.GW.FlightData> Flights = _sortingDAL.FindFlightWithType(BSMs[0].SDT, BSMs[0].FLC, BSMs[0].FLN, BSMs[0].FLX, BSMs[0].BaggageType == "I" ? "A" : "D", out errMessage);
                        tracker.AddTimingStep("Find Flights");

                        if (Flights.Count == 0)
                            HandleNOFlight(item, tracker, TP, ref FinalTargets, ref SelectedTargets, edges, BSM, ref errMessage, BSMs);
                        else
                        {

                            // the flight has been found in database, now we look for destination(s)

                            Flight = Flights[0];

                            if (Flight.FlightType == "A")
                            {
                                #region ARRIVAL_FLIGHT_FOUND
                                tracker.WriteMessage(TrackingLevel.Info, "LOCALFLIGHT", Flight.Flight, Flight.SDT);

                                if (TP.Context == "E")
                                    SelectedTargets = SelectedTargets = GetTargetsToSorter(edges);
                                else
                                {
                                    // set bag status LocalFlight, search for specialsort LocalFlight, if not present send to NotOK
                                    FinalTargets = ManageSpecialSort(TP, tracker, Flight.HDL, BSM.FLC, SpecialSortsEnum.LocalFlight, SpecialSortsEnum.NotOk, out errMessage);
                                    tracker.AddTimingStep("Special:Local");

                                    if (!string.IsNullOrEmpty(errMessage))
                                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

                                    _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.LocalFlight.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                }
                                #endregion
                            }
                            else
                            {
                                #region DEPARTURE_FLIGHT_FOUND
                                // It's a Departure Flight
                                tracker.WriteMessage(TrackingLevel.Info, "FLIGHT_FOUND", Flight.Flight, Flight.ID);
                                tracker.WriteMessage(TrackingLevel.Info, "GENERIC", $"call GetFinalTargets {Flight.ID} {BSM.DES} {BSM.Class} {BSM.OnwardFlight} {BSM.OnwardDES} {BSM.Exception}");
                                targets = _sortingDAL.GetFinalTargets(Flight.ID, BSM.DES, BSM.Class, BSM.OnwardFlight, BSM.OnwardDES, BSM.Exception, string.Empty, out errMessage);
                                tracker.AddTimingStep("Find Targets");
                                if (!string.IsNullOrEmpty(errMessage))
                                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "GetFinalTargets", errMessage);

                                if (targets.Where(t => t.ComponentIndex != 0).Count() == 0)
                                {
                                    #region NOALLOCATION
                                    tracker.WriteMessage(TrackingLevel.Info, "NOALLOCATION", Flight.Flight, Flight.SDT);

                                    if (TP.Context == "E")
                                        SelectedTargets = SelectedTargets = GetTargetsToSorter(edges);
                                    else
                                    {
                                        // TODO: set bag status NOALLOCATION, search for specialsort NOALLOCATION, if not found send to NotOK

                                        FinalTargets = ManageSpecialSort(TP, tracker, Flight.HDL, BSM.FLC, SpecialSortsEnum.NoAllocation, SpecialSortsEnum.NotOk, out errMessage);
                                        tracker.AddTimingStep("Special:No Allocation");

                                        if (!string.IsNullOrEmpty(errMessage))
                                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

                                        _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.NoAllocation.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                    }
                                    #endregion
                                }
                                else
                                {
                                    _trackingDAL.SetItemDailySortingDestination(item, targets[0].IDDestination, out errMessage);
                                    tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', targets.Select(t => t.ToString()).ToList()), "GetFinalTargets");
                                    bool NoATL = false;
                                    bool MergeRedirect = false;
                                    // check for ATL
                                    if (BSM.ATL == "N")
                                    {
                                        // check status of ATL config on target
                                        string TargetATL = targets[0].UseATL ?? "A";
                                        if (TargetATL == "A")
                                        {
                                            configAirlines airline = _sortingDAL.GetAirline(BSM.FLC, out errMessage);
                                            TargetATL = (airline.bATL_BSM ?? false) ? "Y" : "N";
                                        }

                                        if (TargetATL == "Y")
                                        {
                                            NoATL = true;

                                            FinalTargets = ManageSpecialSort(TP, tracker, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.NoATL, SpecialSortsEnum.NotOk, out errMessage);
                                            tracker.AddTimingStep("Special:NO ATL");

                                            if (!string.IsNullOrEmpty(errMessage))
                                                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

                                            _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.NoATL.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                        }
                                    }

                                    if (!NoATL)
                                    {
                                        int? newFlightID = 0;
                                        string MergeRedirectType;
                                        bool bMergeRedirect = _sortingDAL.CheckMergeRedirect(targets[0].idDailySortingFlights, out newFlightID, out MergeRedirectType, out errMessage);
                                        tracker.AddTimingStep("Check MergeRedirect");

                                        if (bMergeRedirect && newFlightID.HasValue)
                                        {
                                            List<FinalTargetsData> newTargets = _sortingDAL.GetFinalTargets(newFlightID.Value, BSM.DES, BSM.Class, BSM.OnwardFlight, BSM.OnwardDES, BSM.Exception, string.Empty, out errMessage);

                                            if (newTargets.Any())
                                            {
                                                targets = newTargets;
                                                foreach (FinalTargetsData ftd in targets)
                                                {
                                                    ftd.SortingReason = MergeRedirectType == "MERGE" ?
                                                        FinalSortReasonEnum.Merge :
                                                        FinalSortReasonEnum.Redirect;
                                                }
                                                tracker.AddTimingStep("After MergeRedirect");

                                                tracker.WriteMessage(TrackingLevel.Debug, "FINALTARGETS", string.Join(',', targets.Select(t => t.ToString()).ToList()), "MergeRedirect");
                                            }
                                        }

                                        if (targets.Where(t => t.isOpen()).Count() == 0)
                                        {
                                            // none of the final destinations are open
                                            if (targets[0].isEarly())
                                            {
                                                #region EARLY
                                                tracker.WriteMessage(TrackingLevel.Info, "BAGEARLY", Flight.Flight, Flight.SDT, targets.Min(t => t.openTS));

                                                if (bEBS)
                                                {
                                                    FinalTargets = ManageSpecialSort(TP, tracker, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.Early, String.Empty, out errMessage);
                                                    tracker.AddTimingStep("Special:Early1");

                                                    if (!FinalTargets.Any() &&
                                                        targets[0].VeryEarlyOffset.HasValue &&
                                                        (targets[0].openTS - DateTime.Now).TotalMinutes > targets[0].VeryEarlyOffset)
                                                    {
                                                        if (targets[0].VeryEarly_specialsort.HasValue)
                                                        {
                                                            FinalTargets = new List<TargetSortData>()
                                                            {
                                                                new TargetSortData()
                                                                {
                                                                    Sorter=null,
                                                                    TargetType="SPECIAL",
                                                                    Target=targets[0].VeryEarly_specialsort.Value,
                                                                    TargetTPid=targets[0].VeryEarly_specialsort.Value,
                                                                    bOverridable=false,
                                                                    SortingReason=FinalSortReasonEnum.Special,
                                                                    SortingReasonSpecial=SpecialSortsEnum.VeryEarly
                                                                }
                                                            };
                                                        }
                                                        else
                                                        {
                                                            FinalTargets = ManageSpecialSort(TP, tracker, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.VeryEarly, SpecialSortsEnum.NotOk, out errMessage);
                                                            tracker.AddTimingStep("Special:VeryEarly");

                                                            if (!string.IsNullOrEmpty(errMessage))
                                                                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);
                                                        }
                                                        _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.VeryEarly.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                                    }

                                                    if (!FinalTargets.Any())
                                                    {
                                                        EBSTargets = _EBS.ProcessBag(TP, item, Flight, targets, _trackingDAL, tracker, out errMessage);
                                                        tracker.AddTimingStep("Special:Early EBS");

                                                        if (!EBSTargets.Any())
                                                            _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.Early.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                                    }
                                                    else
                                                        _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.Early.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);

                                                }
                                                else
                                                {
                                                    FinalTargets = ManageEarly(item, TP, tracker, targets, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.VeryEarly, SpecialSortsEnum.Early, SpecialSortsEnum.NotOk, out errMessage);

                                                    tracker.AddTimingStep("Special:Early2");

                                                    if (!string.IsNullOrEmpty(errMessage))
                                                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageEarly", errMessage);

                                                }

                                                if (!EBSTargets.Any() && !FinalTargets.Any())
                                                {
                                                    FinalTargets = ManageEarly(item, TP, tracker, targets, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.VeryEarly, SpecialSortsEnum.Early, SpecialSortsEnum.NotOk, out errMessage);

                                                    tracker.AddTimingStep("Special:Early3");
                                                    if (!string.IsNullOrEmpty(errMessage))
                                                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);
                                                }
                                                else if (EBSTargets.Count() > 0 && EBSTargets[0].Target == 0)
                                                {
                                                    // check special cases
                                                    // if EBSTargets contains a target 0 and TargetType="FINAL" --> redirect to FinalSort
                                                    // if EBSTargets contains a target 0 and TargetType="VERYEARLY" --> redirect to VERYEARLY special sort
                                                    
                                                    if (EBSTargets[0].TargetType == "FINAL")
                                                        FinalTargets = targets.Where(t => t.isOpen()).Select(t => new TargetSortData() { TargetType = "FINAL", Sorter = t.AggregateName, Target = t.ComponentIndex, TargetTPid = t.idTarget }).ToList();
                                                    if (EBSTargets[0].TargetType == "VERYEARLY")
                                                    {
                                                        if (targets[0].VeryEarly_specialsort.HasValue)
                                                        {
                                                            FinalTargets = new List<TargetSortData>()
                                                            {
                                                                new TargetSortData()
                                                                {
                                                                    Sorter=null,
                                                                    TargetType="SPECIAL",
                                                                    Target=targets[0].VeryEarly_specialsort.Value,
                                                                    TargetTPid=targets[0].VeryEarly_specialsort.Value,
                                                                    bOverridable=false,
                                                                    SortingReason=FinalSortReasonEnum.Special,
                                                                    SortingReasonSpecial=SpecialSortsEnum.VeryEarly
                                                                }
                                                            };
                                                        }
                                                        else
                                                        {
                                                            FinalTargets = ManageSpecialSort(TP, tracker, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.VeryEarly, SpecialSortsEnum.NotOk, out errMessage);
                                                            tracker.AddTimingStep("Special:VeryEarly");

                                                            if (!string.IsNullOrEmpty(errMessage))
                                                                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);
                                                        }
                                                        _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.VeryEarly.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                                    }
                                                    
                                                    EBSTargets.Clear();
                                                }
                                                #endregion
                                            }
                                            else if (targets[0].isLate())
                                            {
                                                #region LATE
                                                if (bEBS)
                                                {
                                                    int iRet = _EBSDAL.BagRemovedFromEBSBooked(item, out errMessage);

                                                    if (!string.IsNullOrEmpty(errMessage))
                                                        WLog(Severity.Error, "ErrRemoveBagEBSBooked", item.ID, iRet, errMessage);
                                                    else if (iRet > 0)
                                                        WLog(Severity.Info, "RemoveBagEBSBooked", item.ID, iRet);
                                                }
                                                tracker.WriteMessage(TrackingLevel.Info, "BAGLATE", Flight.Flight, Flight.SDT, targets.Max(t => t.ClosedTS));
                                                if (TP.Context == "E")
                                                    SelectedTargets = SelectedTargets = GetTargetsToSorter(edges);
                                                else
                                                {
                                                    bRet = ManageSpecialLATECase(item, ref TP, BSM, ref SelectedTargets, tracker, out bSentMES, out bSentOCR, out errMessage);
                                                    if (!bRet)
                                                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialLATECase", errMessage);
                                                    if (SelectedTargets.Any())
                                                        return true;

                                                    FinalTargets = ManageSpecialSort(TP, tracker, (targets.Select(t => t.HDL).FirstOrDefault()) ?? Flight.HDL, BSM.FLC, SpecialSortsEnum.Late, SpecialSortsEnum.NotOk, out errMessage);
                                                    tracker.AddTimingStep("Special:Late");

                                                    if (!string.IsNullOrEmpty(errMessage))
                                                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

                                                    _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.Late.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                                                }
                                                #endregion
                                            }
                                        }
                                        else
                                        {
                                            #region FINAL_SORT
                                            if (bEBS)
                                            {
                                                int iRet = _EBSDAL.BagRemovedFromEBSBooked(item, out errMessage);

                                                if (!string.IsNullOrEmpty(errMessage))
                                                    WLog(Severity.Error, "ErrRemoveBagEBSBooked", item.ID, iRet, errMessage);
                                                else if (iRet > 0)
                                                    WLog(Severity.Info, "RemoveBagEBSBooked", item.ID, iRet);
                                            }

                                            var list = _sortingDAL.GetSpecialSort(TP.TPSorterStart, Flight?.HDL, Flight?.FLC, SpecialSortsEnum.LastMinute, out errMessage);
                                            int? specialLastMinute = list.Count() > 0 ? (int?)list[0].Target : null;

                                                FinalTargets = targets.Where(t => t.isOpen())
                                                    .Select(t => new TargetSortData() { TargetType = "FINAL",
                                                        Sorter = t.AggregateName,
                                                        Target = t.isClosing() ? t.ClosingTarget ?? specialLastMinute ?? t.ComponentIndex : t.ComponentIndex,
                                                        TargetTPid = t.idTarget,
                                                        SortingReason = t.isClosing() ? FinalSortReasonEnum.Closing : t.SortingReason,
                                                        SortingReasonSpecial = t.SortingReasonSpecial,
                                                        AlternativeTarget1 = t.AlternativeTarget1,
                                                        AlternativeTarget2 = t.AlternativeTarget2,
                                                        SortingStrategy = t.SortingStrategy }).ToList();
                                            tracker.AddTimingStep("FinalTargets");
                                            #endregion
                                        }
                                    }
                                }
                                #endregion
                            }
                        }
                    }
                }
            }

            // if item is not OSS will always calculate the minimum screening level in case it's been increased
            if (BSM != null)
            {
                int prevMin = (item.MinimumScreeningLevel ?? -1);

                int? newMin = CalculateMinimumScreeningLevel(targets, Flight, BSM, tracker, out errMessage);
                if ((item.MinimumScreeningLevel ?? 0) <= (newMin ?? 0) && item.MinimumScreeningLevel!=(int)ScreeningLevelRequired.OSS)
                    item.MinimumScreeningLevel = newMin;
                _trackingDAL.SetMinimumScreeningLevel(item, (ScreeningLevelRequired)(item.MinimumScreeningLevel ?? 1), out errMessage);
                tracker.AddTimingStep("SetMinimumScreeningLevel1");

                if (!string.IsNullOrEmpty(errMessage))
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetMinimumScreeningLevel", errMessage);

                if ((item.MinimumScreeningLevel ?? (int)ScreeningLevelRequired.Auto) < (int)ScreeningLevelRequired.HiRisk
                    && item.bCheckOSS)
                {
                    int? preMinScreenLevel = item.MinimumScreeningLevel;

                    int? newMinLevel = CalculateOSS(BSM, tracker, out errMessage);
                    if (newMinLevel == (int)ScreeningLevelRequired.OSS &&
                        prevMin != (newMinLevel ?? -1))
                    {
                        item.MinimumScreeningLevel = newMinLevel;
                        _trackingDAL.SetMinimumScreeningLevel(item, (ScreeningLevelRequired)item.MinimumScreeningLevel, out errMessage);

                        LAP.ScreeningResultMsg res = new LAP.ScreeningResultMsg();
                        res.ScreeningResultDataString = OSSClean();
                        res.LineSeq = (short)TP.ComponentIndexStart;
                        res.Envelope = new LAP.MsgEnvelope();
                        res.Envelope.SourceNode = destReq.Envelope.SourceNode;
                        res.Envelope.DestinationNode = destReq.Envelope.DestinationNode;

                        bMinimumScreeningLevelChange = true;

                        tracker.WriteMessage(TrackingLevel.Debug, "BAG_OSS");
                        ManageScreeningResult(item, res, tracker, out errMessage);
                    }
                }


                if (prevMin != (item.MinimumScreeningLevel ?? -1))
                    bMinimumScreeningLevelChange = true;

                string attr = _trackingDAL.GetItemAttribute(item, "SECURITY_XELEMENT");

                switch (item.MinimumScreeningLevel)
                {
                    case 1: item.CreateBPMXElement = "AUTO"; break;
                    case 2: item.CreateBPMXElement = "HIRISK"; break;
                }

                if ((attr??string.Empty) == item.CreateBPMXElement)
                    item.CreateBPMXElement = string.Empty;

                tracker.AddTimingStep("SetMinimumScreeningLevel2");
            }

            if (!item.MinimumScreeningLevel.HasValue)
            {
                item.MinimumScreeningLevel = 1;
                _trackingDAL.SetMinimumScreeningLevel(item, (ScreeningLevelRequired)(item.MinimumScreeningLevel ?? 1), out errMessage);

                bMinimumScreeningLevelChange = true;
                tracker.AddTimingStep("SetMinimumScreeningLevel3");
            }

            if (TP.CheckSecurity && bMinimumScreeningLevelChange)
            {
                string XRayStatus = null;
                string param = null;
                switch (item.MinimumScreeningLevel)
                {
                    case 0:
                        XRayStatus = param = "NON";
                        break;
                    case 1:
                        XRayStatus = "";
                        param = "Auto";
                        break;
                    case 2:
                        XRayStatus = param = "SEL";
                        break;
                }

                if(XRayStatus != null && param != null)
                    _trackingDAL.AddBagTracking(item, BagTrackingEventType.SecurityReq, (string)null, TP.TPSorterStart, TP.EdgeStartID, XRayStatus, out errMessage, "SecurityReq", param);
                tracker.AddTimingStep("SetMinimumScreeningLevel BagTracking");
            }

            if ((item.MinimumScreeningLevel ?? -1) == (int)ScreeningLevelRequired.HiRisk && item.LastSecurityStatusFromPLC == Level1Clean())
            {
                // reset item security
                bool bRet2 = _trackingDAL.ResetItemSecurity(item, out errMessage);
                tracker.WriteMessage(TrackingLevel.Error, "GENERIC", $"Reset security {bRet2}");
                if (!bRet2)
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ResetItemSecurity", errMessage);
                item.bSecurityStatus = false;
                item.LastSecurityStatusFromPLC = null;
                tracker.AddTimingStep("ResetItemSecurity");
            }

            #region KID
            if (GenControlId && string.IsNullOrEmpty(KID))
            {
                KID = GenerateControlID(item, null, out errMessage);
                if (string.IsNullOrEmpty(KID))
                {
                    tracker.WriteMessage(TrackingLevel.Error, "ERRORGENKID", item.ID);
                }
            }
            #endregion

            return true;
        }

        public virtual void HandleNOFlight(GW.Item item, GW.ITrackingEvents tracker, SortingEdgeData TP, ref List<TargetSortData> FinalTargets, ref List<TargetSortData> SelectedTargets, List<SortingEdgeData> edges, BSMData BSM, ref string errMessage, List<BSMData> BSMs)
        {
            #region NOFLIGHT
            tracker.WriteMessage(TrackingLevel.Info, "NOFLIGHT", BSMs[0].Flight, BSMs[0].SDT);

            if (TP.Context == "E")
            {
                SelectedTargets = GetTargetsToSorter(edges);
                return;
            }
            
            // set bag status NOFLIGHT, search for specialsort NOFLIGHT
            // set Final destination to NOFLIGHT
            // in case NOFLIGHT is not defined, send to NOTOK
            configAirlines airline = _sortingDAL.GetAirline(BSM.FLC, out errMessage);

            FinalTargets = ManageSpecialSort(TP, tracker, airline?.HDL, BSM.FLC, SpecialSortsEnum.NoFlight, SpecialSortsEnum.NotOk, out errMessage);
            tracker.AddTimingStep("Special:NoFlight");

            if (!string.IsNullOrEmpty(errMessage))
                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "ManageSpecialSort", errMessage);

            _trackingDAL.SetItemBagDestinationStatus(item, BagDestinationStatus.NoFlight.ToString(), TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
            #endregion
        }

        protected int? CalculateMinimumScreeningLevel(List<FinalTargetsData> targets, GW.FlightData Flight, BSMData BSM, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            configAirports airportDES = null;
            configAirports airportORG = null;
            configAirlines airline = null;
            string FlightSecMode = string.Empty;

            string BSMSecurity = string.Empty;
            int minLevel = 1;
            try
            {
                if (targets != null && targets.Any())
                    FlightSecMode = targets[0].SecurityMode;

                if (BSM != null)
                {
                    airline = _sortingDAL.GetAirline(BSM.FLC, out errMessage);

                    if (!string.IsNullOrEmpty(BSM.DES))
                        airportDES = _sortingDAL.GetAirport(BSM.DES, out errMessage);

                    if (!string.IsNullOrEmpty(BSM.Origin_Airport))
                        airportORG = _sortingDAL.GetAirport(BSM.Origin_Airport, out errMessage);

                    BSMSecurity = BSM.SecurityScreeningInfo;
                }

                if (!string.IsNullOrEmpty(BSMSecurity) && BSMSecurity.Contains("SEL") && (airline?.ProcessXElement ?? false))
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateMinimumScreening", "BSM", BSMSecurity, (int)ScreeningLevelRequired.HiRisk);
                    minLevel = (int)ScreeningLevelRequired.HiRisk;
                }

                if (!string.IsNullOrEmpty(FlightSecMode) && FlightSecMode == "M")
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateMinimumScreening", "Flight", FlightSecMode, (int)ScreeningLevelRequired.HiRisk);
                    minLevel = (int)ScreeningLevelRequired.HiRisk;
                }

                if (airportDES != null && airportDES.SecurityModeDestination == "M")
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateMinimumScreening", "AirportDES", airportDES.SecurityModeDestination, (int)ScreeningLevelRequired.HiRisk);
                    minLevel = (int)ScreeningLevelRequired.HiRisk;
                }

                if (airportORG != null && airportORG.SecurityModeOrigin == "M")
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateMinimumScreening", "AirportORG", airportORG.SecurityModeOrigin, (int)ScreeningLevelRequired.HiRisk);
                    minLevel = (int)ScreeningLevelRequired.HiRisk;
                }

                if (airline != null && airline.SecurityMode == "M")
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateMinimumScreening", "Airline", airline.SecurityMode, (int)ScreeningLevelRequired.HiRisk);
                    minLevel = (int)ScreeningLevelRequired.HiRisk;
                }

                return minLevel;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public virtual int? CalculateOSS(BSMData BSM, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            configAirports airportORG = null;
            configAirlines airline = null;
            string BSMSecurity = string.Empty;
            int? minLevel = null;
            try
            {
                if (!string.IsNullOrEmpty(BSM.Origin_Airport))
                    airportORG = _sortingDAL.GetAirport(BSM.Origin_Airport, out errMessage);

                airline = _sortingDAL.GetAirline(BSM.FLC, out errMessage);

                BSMSecurity = BSM.SecurityScreeningInfo;

                if (!string.IsNullOrEmpty(BSMSecurity) && BSMSecurity.Contains("NON") && (airline?.ProcessXElement ?? false))
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateOSS", "BSM", BSMSecurity, (int)ScreeningLevelRequired.OSS);
                    minLevel = (int)ScreeningLevelRequired.OSS;
                }

                if (airportORG != null && airportORG.SecurityModeOrigin == "O")
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CalculateOSS", "AirportORG", airportORG.SecurityModeOrigin, (int)ScreeningLevelRequired.OSS);
                    minLevel = (int)ScreeningLevelRequired.OSS;
                }

                return minLevel;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public virtual bool SpecialFilterFinalTargets(GW.Item item, SortingEdgeData TP, ref List<TargetSortData> FinalSorts, GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public virtual bool CheckFinalTargetsSorterStatus(SortingEdgeData TP,ref List<TargetSortData> FinalSorts, GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        protected void ManageItemDestinations(GW.Item item, List<TargetSortData> FinalSorts, string TargetType, bool overridable = true)
        {
            string errMessage = string.Empty;
            //// TODO:set new finalDestinations in item

            //_trackingDAL.SetItemDestinations(item.ID, FinalSorts.Select(fs => fs.Target).ToList(), TargetType, true, true);

            List<string> ItemFinalTargets = item.ItemDestinations.Where(id => id.bActive && id.DestinationType == TargetType).Select(id => $"{id.IDDestination}-{id.DestinationType}-{id.SortingReason}-{id.SortingReasonSpecial}").ToList();
            List<TargetSortData> retList = new List<TargetSortData>();

            if (FinalSorts?.Where(fs => fs.TargetType == TargetType).Count() <= 0)
                return;

            List<GW.ItemDestination> FinalDestList = new List<GW.ItemDestination>();
            if (ItemFinalTargets.Intersect(FinalSorts.Select(ft => $"{ft.Target}-{ft.TargetType}-{ft.SortingReason}-{ft.SortingReasonSpecial}").ToList()).Count() == FinalSorts.Where(fs => fs.TargetType == TargetType).Count())
                return;
            
            foreach (TargetSortData td in FinalSorts.Where(fs => fs.TargetType == TargetType))
            {
                GW.ItemDestination id = new GW.ItemDestination()
                {
                    IDItem = item.ID,
                    VID = item.LastVid,
                    Sorter = td.Sorter,
                    bOverridable = overridable,
                    IDDestination = td.Target,
                    Timestamp = _clock.Now,
                    bActive = true,
                    OrderIndex = 1,
                    DestinationType = TargetType,
                    IDMessage = 0,
                    SortingReason = td.SortingReason,
                    SortingReasonSpecial = td.SortingReasonSpecial
                };

                FinalDestList.Add(id);
            }
            _sortingDAL.SaveItemDestinations(FinalDestList, out errMessage);
        }

        public virtual List<TargetSortData> ManageRecirculationsNew(SortingEdgeData TP, GW.Item i, List<TargetSortData> targets, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            List<Tuple<int, bool>> ret = new List<Tuple<int, bool>>();
            List<Tuple<int, bool>> retAlternative = new List<Tuple<int, bool>>();
            List<TargetSortData> tsdToRemove = new List<TargetSortData>();
            List<SortingDestinationConfig> blockedDest = new List<SortingDestinationConfig>();
            bool isBlocked = false;
            string recircSpecialTarget = null;

            errMessage = string.Empty;
            try
            {
                // get sorting destinations congfiguration
                List<SortingDestinationConfig> sortDests = _sortingDAL.GetSortingDestinationConfigs(out errMessage);

                blockedDest = sortDests.
                                Where(sd => targets.Select(tt => tt.Target).ToList().Contains(sd.ComponentIndex)
                                && sd.IDPLCNode == TP.PLCNodeStart && !sd.OperationalStatus).ToList();

                if (TP.UseAlternativeOnBlockedTarget && blockedDest.Count == targets.Count)
                {
                    SortingDestinationConfig sdc = sortDests.Where(sd => sd.ComponentIndex == targets[0].Target).FirstOrDefault();

                    int newtg = 0;
                    string spec = sdc.t1Comp.ToString();
                    if (sdc.T1id.HasValue)
                        newtg = sdc.t1Comp;
                    else
                    {
                        List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort1, SpecialSortsEnum.NotOk, out errMessage);
                        spec = sdc.SpecialSort1;
                        if (specialTargets.Any())
                        {
                            recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                            newtg = specialTargets[0].Target;
                        }
                    }
                    
                    tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtg, spec, sdc.T1rec);

                    List<TargetSortData> tsdList = _sortingDAL.GetTargetSortsFromTargetList(TP.PLCNodeStart.Value, new List<int>() { newtg }, out errMessage);
                    return tsdList;
                }

                if (!TP.MasterTP.HasValue)
                {
                    foreach (SortingDestinationConfig blocked in blockedDest)
                    {
                        LAP.SortResultMsg sortres = new LAP.SortResultMsg();
                        sortres.CheckPoint = (ushort)TP.ComponentIndexStart;
                        sortres.Destination = (short)blocked.ComponentIndex;
                        sortres.Reason = 20;
                        sortres.Result = 3;
                        sortres.Type = 1;
                        sortres.Cell = (short)i.CellId;
                        sortres.Envelope = new LAP.MsgEnvelope();
                        sortres.Envelope.SourceNode = (short)TP.PLCNodeStart;

                        isBlocked = true;
                        bool isFinal;
                        SorterStart startSorter;
                        SetResult(i, sortres, tracker, out isFinal, out startSorter, out errMessage);
                    }
                }

                List<RecirculationCount> recCount = _sortingDAL.GetItemRecirculationCount(TP.PLCNodeStart.Value, i, out errMessage);

                if (!recCount.Any())
                    return targets;

                foreach (int t in targets.Select(t => t.Target))
                {
                    // check if the number of recirculations on this target exceeds the configured one
                    SortingDestinationConfig sdc = sortDests.Where(sd => sd.ComponentIndex == t && sd.IDPLCNode == TP.PLCNodeStart).FirstOrDefault();
                    Dictionary<int, int> dictRec = recCount.ToDictionary(x => x.ComponentIndex, x => x.cnt);

                    if (sdc == null || dictRec == null)
                    {
                        ret.Add(new Tuple<int, bool>(t, false));
                        continue;
                    }

                    // check primary target recirc count 

                    int recircCount;
                    int oldtarget = t;
                    int newtarget = t;
                    dictRec.TryGetValue(newtarget, out recircCount);

                    // if there is an alternative target configured and recirculationcount exeeds the count
                    if ((recircCount >= sdc.T1rec || TP.ComponentTypeStart == "MES") && (sdc.T1id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort1)))
                    {
                        string spec = sdc.t1Comp.ToString();
                        if (sdc.T1id.HasValue)
                            newtarget = sdc.t1Comp;
                        else
                        {
                            spec = sdc.SpecialSort1;
                            List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort1, SpecialSortsEnum.NotOk, out errMessage);

                            if (specialTargets.Any())
                            {
                                recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                newtarget = specialTargets[0].Target;
                            }
                        }

                        tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec, sdc.T1rec);

                        dictRec.TryGetValue(newtarget, out recircCount);
                        if (recircCount > sdc.T2rec && (sdc.T2id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort2)))
                        {
                            string spec2 = sdc.t2Comp.ToString();
                            if (sdc.T2id.HasValue)
                                newtarget = sdc.t2Comp;
                            else
                            {
                                spec2 = sdc.SpecialSort2;
                                List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort2, SpecialSortsEnum.NotOk, out errMessage);

                                if (specialTargets.Any())
                                {
                                    recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                    newtarget = specialTargets[0].Target;
                                }
                            }

                            tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec2, sdc.T2rec);

                            dictRec.TryGetValue(newtarget, out recircCount);
                            if (recircCount > sdc.T3rec && (sdc.T3id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort3)))
                            {
                                string spec3 = sdc.t3Comp.ToString();
                                if (sdc.T3id.HasValue)
                                    newtarget = sdc.t3Comp;
                                else
                                {
                                    spec3 = sdc.SpecialSort3;
                                    List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort3, SpecialSortsEnum.NotOk, out errMessage);

                                    if (specialTargets.Any())
                                    {
                                        recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                        newtarget = specialTargets[0].Target;
                                    }

                                }

                                tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec3, sdc.T3rec);
                            }
                        }
                    }

                    if (oldtarget != newtarget)
                        tracker.WriteMessage(TrackingLevel.Info, "RECIRCULATION", oldtarget, newtarget);
                    ret.Add(new Tuple<int, bool>(newtarget, oldtarget != newtarget));
                }

                if (!(ret.Where(t => t.Item2).Count() == ret.Count()) && ret.Where(t => t.Item2).Count() > 0)
                {
                    retAlternative = ret.Where(t => t.Item2).ToList();
                    ret = ret.Where(t => !t.Item2).ToList();
                }


                bool BlockedRemoved = false;
                List<TargetSortData> tsdList2 = _sortingDAL.GetTargetSortsFromTargetList(TP.PLCNodeStart.Value, ret.Select(t => t.Item1).ToList(), out errMessage);

                foreach (SortingDestinationConfig blocked in blockedDest)
                {
                    TargetSortData tsr = tsdList2.Where(t => t.Target == blocked.ComponentIndex).FirstOrDefault();
                    tsdList2.Remove(tsr);
                    BlockedRemoved = true;
                }

                if (!tsdList2.Any())
                {
                    tsdList2 = _sortingDAL.GetTargetSortsFromTargetList(TP.PLCNodeStart.Value, retAlternative.Select(t => t.Item1).ToList(), out errMessage);

                    foreach (TargetSortData tsd in tsdList2)
                    {
                        tsd.SortingReason = isBlocked ? FinalSortReasonEnum.AlternativeBlock : FinalSortReasonEnum.AlternativeFault;
                        tsd.SortingReasonSpecial = recircSpecialTarget;
                    }
                }
                else if ((ret.Where(t => t.Item2).Count() == ret.Count()))
                {
                    foreach (TargetSortData tsd in tsdList2)
                    {
                        tsd.SortingReason = FinalSortReasonEnum.AlternativeFault;
                        tsd.SortingReasonSpecial = recircSpecialTarget;
                    }
                }

                return tsdList2;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return targets;
            }
        }

        public virtual List<TargetSortData> ManageRecirculations(SortingEdgeData TP, GW.Item i, List<TargetSortData> targets, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            List<int> ret = new List<int>();
            List<TargetSortData> tsdToRemove = new List<TargetSortData>();
            List<SortingDestinationConfig> blockedDest = new List<SortingDestinationConfig>();
            bool isBlocked = false;
            string recircSpecialTarget = null;
            bool isAlternative = false;

            errMessage = string.Empty;
            try
            {
                // get sorting destinations congfiguration
                List<SortingDestinationConfig> sortDests = _sortingDAL.GetSortingDestinationConfigs(out errMessage);

                blockedDest = sortDests.
                                Where(sd => targets.Select(tt => tt.Target).ToList().Contains(sd.ComponentIndex)
                                && sd.IDPLCNode == TP.PLCNodeStart && !sd.OperationalStatus).ToList();


                if (TP.UseAlternativeOnBlockedTarget && blockedDest.Count == targets.Count)
                {
                    SortingDestinationConfig sdc = sortDests.Where(sd => sd.ComponentIndex == targets[0].Target).FirstOrDefault();

                    int newtg = 0;
                    string spec = sdc.t1Comp.ToString();

                    if (sdc.T1id.HasValue)
                        newtg = sdc.t1Comp;
                    else
                    {
                        string FLC = null;
                        string HDL = null;
                        if (i.idFlight.HasValue)
                        {
                            GW.FlightData fd = _sortingDAL.GetFlight(i.idFlight.Value, out errMessage);
                            FLC = fd.FLC;
                            HDL = fd.HDL;
                        }

                        List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, HDL, FLC, sdc.SpecialSort1, SpecialSortsEnum.NotOk, out errMessage);

                        if (specialTargets.Any())
                        {
                            recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                            newtg = specialTargets[0].Target;
                        }

                        spec = sdc.SpecialSort1;
                    }

                    tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtg, spec, sdc.T1rec);

                    List<TargetSortData> tsdList = _sortingDAL.GetTargetSortsFromTargetList(TP.PLCNodeStart.Value, new List<int>() { newtg }, out errMessage);
                    return tsdList;
                }

                if (!TP.MasterTP.HasValue)
                {
                    foreach (SortingDestinationConfig blocked in blockedDest)
                    {
                        LAP.SortResultMsg sortres = new LAP.SortResultMsg();
                        sortres.CheckPoint = (ushort)TP.ComponentIndexStart;
                        sortres.Destination = (short)blocked.ComponentIndex;
                        sortres.Reason = 20;
                        sortres.Result = 3;
                        sortres.Type = 1;
                        sortres.Cell = (short)i.CellId;
                        sortres.Envelope = new LAP.MsgEnvelope();
                        sortres.Envelope.SourceNode = (short)TP.PLCNodeStart;

                        isBlocked = true;
                        bool isFinal;
                        SorterStart startSorter;
                        SetResult(i, sortres, tracker, out isFinal, out startSorter, out errMessage);
                    }
                }
                List<RecirculationCount> recCount = _sortingDAL.GetItemRecirculationCount(TP.PLCNodeStart.Value, i, out errMessage);

                if (!recCount.Any())
                    return targets;

                foreach (int t in targets.Select(t => t.Target))
                {
                    // check if the number of recirculations on this target exceeds the configured one
                    SortingDestinationConfig sdc = sortDests.Where(sd => sd.ComponentIndex == t && sd.IDPLCNode == TP.PLCNodeStart).FirstOrDefault();
                    Dictionary<int, int> dictRec = recCount.ToDictionary(x => x.ComponentIndex, x => x.cnt);

                    if (sdc == null || dictRec == null)
                        continue;

                    // check primary target recirc count 

                    int recircCount;
                    int oldtarget = t;
                    List<int> newtarget = new List<int> { t };
                    dictRec.TryGetValue(newtarget[0], out recircCount);

                    // if there is an alternative target configured and recirculationcount exeeds the count
                    if ((recircCount >= sdc.T1rec || TP.ComponentTypeStart == "MES") && (sdc.T1id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort1)))
                    {
                        string spec = sdc.t1Comp.ToString();
                        if (sdc.T1id.HasValue)
                            newtarget = _trackingDAL.GetComponentGroup(sdc.t1Comp, TP.PLCNodeStart ?? 0);
                        else
                        {
                            spec = sdc.SpecialSort1;

                            string FLC = null;
                            string HDL = null;
                            if (i.idFlight.HasValue)
                            {
                                GW.FlightData fd = _sortingDAL.GetFlight(i.idFlight.Value, out errMessage);
                                FLC = fd.FLC;
                                HDL = fd.HDL;
                            }

                            List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, HDL, FLC, sdc.SpecialSort1, SpecialSortsEnum.NotOk, out errMessage);

                            if (specialTargets.Any())
                            {
                                recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                newtarget = specialTargets.Select(t => t.Target).ToList();
                            }
                        }

                        tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec, sdc.T1rec);

                        dictRec.TryGetValue(newtarget[0], out recircCount);
                        if (recircCount > sdc.T2rec && (sdc.T2id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort2)))
                        {
                            string spec2 = sdc.t2Comp.ToString();
                            if (sdc.T2id.HasValue)
                                newtarget = new List<int> { sdc.t2Comp };
                            else
                            {
                                spec2 = sdc.SpecialSort2;
                                List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort2, SpecialSortsEnum.NotOk, out errMessage);

                                if (specialTargets.Any())
                                {
                                    recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                    newtarget = specialTargets.Select(t => t.Target).ToList();
                                }
                            }

                            tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec2, sdc.T2rec);

                            dictRec.TryGetValue(newtarget[0], out recircCount);
                            if (recircCount > sdc.T3rec && (sdc.T3id.HasValue || !string.IsNullOrEmpty(sdc.SpecialSort3)))
                            {
                                string spec3 = sdc.t3Comp.ToString();
                                if (sdc.T3id.HasValue)
                                    newtarget = new List<int> { sdc.t3Comp };
                                else
                                {
                                    spec3 = sdc.SpecialSort3;
                                    List<TargetSortData> specialTargets = ManageSpecialSort(TP, tracker, null, null, sdc.SpecialSort3, SpecialSortsEnum.NotOk, out errMessage);

                                    if (specialTargets.Any())
                                    {
                                        recircSpecialTarget = specialTargets[0].SortingReasonSpecial;
                                        newtarget = specialTargets.Select(t => t.Target).ToList();
                                    }
                                }

                                tracker.WriteMessage(TrackingLevel.Debug, "RECIRCULATION_DEBUG", newtarget, spec3, sdc.T3rec);
                            }
                        }
                    }

                    ret.AddRange(newtarget);

                    if (!newtarget.Contains(oldtarget))
                    {
                        tracker.WriteMessage(TrackingLevel.Info, "RECIRCULATION", oldtarget, newtarget);
                        isAlternative = true;
                    }
                }

                if (isAlternative)
                {
                    List<TargetSortData> tsdList = _sortingDAL.GetTargetSortsFromTargetList(TP.PLCNodeStart.Value, ret, out errMessage);

                    foreach (SortingDestinationConfig blocked in blockedDest)
                    {
                        TargetSortData tsr = tsdList.Where(t => t.Target == blocked.ComponentIndex).FirstOrDefault();
                        tsdList.Remove(tsr);
                    }

                    foreach (TargetSortData tsd in tsdList)
                    {
                        tsd.SortingReason = isBlocked ? FinalSortReasonEnum.AlternativeBlock : FinalSortReasonEnum.AlternativeFault;
                        tsd.SortingReasonSpecial = recircSpecialTarget;
                    }

                    return tsdList;
                }
                
                foreach (SortingDestinationConfig blocked in blockedDest)
                {
                    TargetSortData tsr = targets.Where(t => t.Target == blocked.ComponentIndex).FirstOrDefault();
                    targets.Remove(tsr);
                }

                return targets;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return targets;
            }
        }
        
        public virtual List<TargetSortData> GetTargetBulky(int Target)
        {
            string errMessage = string.Empty;

            int bulkyTarget = _sortingDAL.GetBulkyTarget(Target, out errMessage);

            GW.Component c = _trackingDAL.GetComponentByID(bulkyTarget, 6, out errMessage);

            return new List<TargetSortData>() { new TargetSortData() { bOverridable = false, Sorter = null,
                    Target = c.ComponentIndex, TargetTPid = c.TrackingPointID??0,
                    SortingReason=string.Empty, SortingReasonSpecial= string.Empty, TargetType = "FINAL" }};
        }

        public virtual List<int> ManageMESDestinations(GW.Item i, MesCodeBaggageMessage MesCodeBag, LAP.BaseMessage bm, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            List<int> retList = new List<int>();
            List<GW.ItemDestination> itemDestinations = new List<GW.ItemDestination>();
            SortingEdgeData TP = null;
            SortingEdgeData TPOut = null;
            string sorter = null;

            List<SortingEdgeData> edges = GetSortingEdges(i, bm, tracker, ref TP, out TPOut, out errMessage);
            
            if (MesCodeBag.DestinationTargets?.Any() == true)
            {
                // there are final targets
                try
                {
                    itemDestinations = _sortingDAL.ConvertToItemDestinations(i, MesCodeBag, out errMessage);
                    _sortingDAL.SaveItemDestinations(itemDestinations, out errMessage);
                    i.ItemDestinations = itemDestinations;
                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Final Item Destinations: " + string.Join(",", itemDestinations));
                }
                catch (Exception ex)
                {
                    errMessage = ex.CompleteMessage();
                    return new List<int> { 0 };
                }
            } // end if normal final destinations
            else if (!string.IsNullOrEmpty(MesCodeBag.SpecialSort))
            {
                // TODO: Get ItemDestinations for Special Sort
                string FLC = string.Empty;
                List<BSMData> BSMs = _sortingDAL.FindBSM(new List<string> { MesCodeBag.BagId }, out errMessage);

                string bsmFoundText = "not found";
                if (BSMs.Count() == 1)
                {
                    bsmFoundText = $"found {BSMs[0].ID}";
                    FLC = BSMs[0].FLC;
                    //_trackingDAL.SetBagId(i, BSMs[0].ID, BSMs[0].BagId, BSMs[0].SDT, out errMessage);
                    //_trackingDAL.SetFlight(i, BSMs[0].SDT, BSMs[0].Flight, out errMessage);
                    //_trackingDAL.SetItemBagReadStatus(i, string.Empty, out errMessage);
                    //_trackingDAL.SetItemBagDestinationStatus(i, "BagOk", "", 0, BSMs[0].ID, out errMessage); //TBD: MES Tracking point id

                    _trackingDAL.SetItemBagStatus(i, BSMs[0].ID, BSMs[0].BagId, BSMs[0].BaggageType, BSMs[0].SDT, BSMs[0].Flight, BSMs[0].DES, BSMs[0].FLC, BSMs[0].FLN, String.Empty, "BagOk", true, "", 0, out errMessage);
                }
                
                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"BSM for BagId {MesCodeBag.BagId} {bsmFoundText}");

                if (TP != null && !string.IsNullOrEmpty(TP.TPSorterStart))
                    sorter = TP.TPSorterStart;

                List<TargetSortData> tsdList = ManageSpecialSort(TP, tracker, null, FLC, MesCodeBag.SpecialSort, SpecialSortsEnum.NotOk, out errMessage);
                foreach (TargetSortData tsd in tsdList)
                {
                    GW.ItemDestination id = new GW.ItemDestination();
                    id.IDItem = i.ID;
                    id.IDDestination = tsd.Target;
                    id.Sorter = tsd.Sorter;
                    id.bOverridable = MesCodeBag.Overridable;
                    id.bActive = true;
                    id.OrderIndex = 1;
                    id.Timestamp = _clock.Now;
                    id.DestinationType = "SPECIAL";
                    id.SortingReason = FinalSortReasonEnum.Special;
                    id.SortingReasonSpecial = tsd.SortingReasonSpecial;

                    itemDestinations.Add(id);
                }

                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Item Destinations after special sort: " + string.Join(",", itemDestinations));
                _sortingDAL.SaveItemDestinations(itemDestinations, out errMessage);
            }

            i.ItemDestinations = itemDestinations;
            return CalculateDestinations("MES", i, bm, tracker, out string IATAcode, out string ControlId, out TP, out errMessage);
        }

        public virtual GW.SecurityStatusLookup ManageScreeningResult(GW.Item item, LAP.ScreeningResultMsg res, GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                // get security translation
                GW.SecurityStatusLookup secval = _trackingDAL.GetSecurityStatusLookup(res.ScreeningResultDataString, out errMessage);

                if (secval == null)
                    return null;

                GW.ItemSecurity itemsec = new GW.ItemSecurity();
                itemsec.IDItem = item.ID;
                itemsec.SecurityStatusfromPLC = res.ScreeningResultDataString;
                itemsec.ComponentIndex = res.ComponentIndex;
                itemsec.timestamp = DateTime.Now;
                itemsec.bSecurityStatus = secval.bSecurityStatus;
                itemsec.VID = res.VID;

                TrackingPoint tp = _trackingDAL.GetTPfromComponent(res.ComponentIndex);

                BPMSecurityInfo bpmsecinfo = GetBPMSecurityInfo(tp, itemsec, out errMessage);

                bool BPMPending = false;
                bool bRet;

                if (bpmsecinfo != null)
                {
                    tracker.WriteMessage(TrackingLevel.Info, "CreateBPM", "Security", item.ID, res.Envelope.SourceNode, res.ComponentIndex, res.ComponentIndex, null, DateTime.Now.ToString("o", CultureInfo.InvariantCulture));
                    bRet = _BPM.CreateBPMForComponent("Security", item, res.Envelope.SourceNode, res.ComponentIndex, res.ComponentIndex, null, bpmsecinfo, DateTime.Now, out BPMPending, out errMessage);
                    tracker.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, res.ComponentIndex, errMessage);
                    itemsec.BPMPending = BPMPending;
                }

                bRet = _trackingDAL.SetItemSecurity(item, itemsec, out errMessage);
                if (!bRet)
                    return null;

                int TPid = 0;
                string TPSorter = string.Empty;

                if (tp != null)
                {
                    TPSorter = tp.TPSorter;
                    TPid = tp.ID;
                }

                _trackingDAL.AddBagTracking(BagTrackingEventType.XRay, item.BagId,
                                            item.LastVid, item.ControlId, item.Flight, item.SDT, TPSorter, item.CellId, res.ComponentIndex, TPid, _sortingDAL.ConvertScreeningResultToDescription(res.ScreeningResultDataString), out errMessage, "XRay", res.ComponentIndex);

                return secval;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public virtual bool SetContamination(GW.Item item, LAP.TrackingEventNotificationMsg ten, GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            string TPName = string.Empty;

            try
            {
                // get security translation
                GW.SecurityStatusLookup secval = _trackingDAL.GetSecurityStatusLookup("109", out errMessage);

                if (secval == null)
                    return false;

                GW.ItemSecurity itemsec = new GW.ItemSecurity();
                itemsec.IDItem = item.ID;
                itemsec.SecurityStatusfromPLC = "109";
                itemsec.ComponentIndex = ten.ComponentIndex;
                itemsec.timestamp = DateTime.Now;
                itemsec.bSecurityStatus = secval.bSecurityStatus;
                itemsec.VID = ten.VID;

                bool bRet = _trackingDAL.SetItemSecurity(item, itemsec, out errMessage);
                if (!bRet)
                    return false;

                bool BPMPending;

                tracker.WriteMessage(TrackingLevel.Info, "CreateBPM", "Security", item.ID, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, DateTime.Now.ToString("o", CultureInfo.InvariantCulture));
                bRet = _BPM.CreateBPMForComponent("Security", item, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, null, DateTime.Now, out BPMPending, out errMessage);
                tracker.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, ten.ComponentIndex, errMessage);
                itemsec.BPMPending = BPMPending;

                int? TPid = _trackingDAL.TPfromComponent(ten.TrackingPoint);
                TrackingPoint tp = _trackingDAL.GetTPfromComponent(ten.ComponentIndex);

                if (string.IsNullOrEmpty(TPName))
                    TPName = tp.TPName;
                if (string.IsNullOrEmpty(TPName))
                    TPName = ten.TrackingPoint.ToString();

                if (ten.VID != 0)
                    _trackingDAL.AddBagTracking(BagTrackingEventType.Contaminated, item.BagId,
                                                item.LastVid, item.ControlId, item.Flight, item.SDT, string.Empty, item.CellId, string.Empty,
                                                TPid, _sortingDAL.ConvertScreeningResultToDescription("109"), ten.TrackingEvent * 100 + ten.Reason, out errMessage, "Contamination", TPName);

                for (int i = 0; i < ten.N_Obj; i++)
                {
                    long vidContamined = 0;
                    switch (i)
                    {
                        case 0: vidContamined = (long)ten.ObjIDDate1 * 1000000000 + (long)ten.ObjID1; break;
                        case 1: vidContamined = (long)ten.ObjIDDate2 * 1000000000 + (long)ten.ObjID2; break;
                        case 2: vidContamined = (long)ten.ObjIDDate3 * 1000000000 + (long)ten.ObjID3; break;
                    }

                    if (vidContamined == 0)
                        return false;

                    GW.Item itemContamined = _trackingDAL.GetItemByLastVID(vidContamined, out errMessage);

                    if (itemContamined == null)
                    {
                        List<string> errItem = new List<string>();
                        LAP.TrackingEventNotificationMsg ten2 = ten;
                        ten2.ObjID = ten.ObjID1;
                        ten2.ObjIDDate = ten.ObjIDDate1;
                        itemContamined = _trackingDAL.InsertTrackingItem(ten2, tracker, ten.ComponentIndex, out errItem);
                        if (itemContamined == null)
                            //no Item found in DB
                            return false;
                    }

                    GW.ItemSecurity itemsec2 = new GW.ItemSecurity();
                    itemsec2.IDItem = itemContamined.ID;
                    itemsec2.SecurityStatusfromPLC = "109";
                    itemsec2.ComponentIndex = ten.ComponentIndex;
                    itemsec2.timestamp = DateTime.Now;
                    itemsec2.bSecurityStatus = secval.bSecurityStatus;
                    itemsec2.VID = itemContamined.LastVid;

                    bRet = _trackingDAL.SetItemSecurity(itemContamined, itemsec2, out errMessage);
                    if (!bRet)
                        return false;

                    tracker.WriteMessage(TrackingLevel.Info, "CreateBPM", "Security", itemContamined.ID, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, DateTime.Now.ToString("o", CultureInfo.InvariantCulture));
                    bRet = _BPM.CreateBPMForComponent("Security", itemContamined, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, null, DateTime.Now, out BPMPending, out errMessage);
                    tracker.WriteMessage(TrackingLevel.Info, bRet ? "BPMCreated" : "ErrorCreatingBPM", item.ID, ten.ComponentIndex, errMessage);

                    _trackingDAL.AddBagTracking(BagTrackingEventType.Contaminated, item.BagId,
                                                itemContamined.LastVid, itemContamined.ControlId, itemContamined.Flight, itemContamined.SDT, string.Empty, itemContamined.CellId, string.Empty,
                                                TPid, _sortingDAL.ConvertScreeningResultToDescription("109"), ten.TrackingEvent * 100 + ten.Reason, out errMessage, "Contamination", TPName);
                }

                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public virtual bool HBSSubsystemStatus(LAP.SubsystemStatusMsg ssm, Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }
        
        public virtual string GenerateControlID(GW.Item i, int? idBSM, out string errMessage)
        {
            errMessage = string.Empty;
            return string.Empty;
        }
        
        public virtual List<GW.DailySorting> GetDailySortingByDate(DateTime SortplanDate, out string errMessage)
        {
            errMessage = string.Empty;
            return new List<GW.DailySorting>();
        }
        
        public virtual bool AutomaticReleaseSortplan(DateTime SortplanDate, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public List<ClosingTarget> GetClosingFlightTargets(out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                return _sortingDAL.GetClosingTargets(out errMessage);
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ClosingTarget>();
            }
        }

        public virtual bool GetSecurityTargets(SortingEdgeData TP, DataAccess.GW.ITrackingEvents tracker, GW.Item item, LAP.BaseMessage destReq, GW.FlightData Flight, BSMData BSM, ref List<TargetSortData> SelectedTargets, ref List<TargetSortData> FinalTargets, ref List<TargetSortData> EBSTargets, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public virtual string GetCodeXRay(string IATA, string ControlID)
        {
            return IATA;
        }

        public virtual string CleanSecurityStatus()
        {
            return String.Empty;
        }
        
        public virtual string Level1Clean()
        {
            return String.Empty;
        }
        
        public virtual string OSSClean()
        {
            return String.Empty;
        }

        public virtual BPMSecurityInfo GetBPMSecurityInfo(TrackingPoint tp, ItemSecurity itemsec, out string errMessage)
        {
            errMessage = String.Empty;
            string errMessage2 = string.Empty;
            BPMSecurityInfo bpminfo = null;

            GW.SecurityStatusEnumDescription secInfo = _sortingDAL.GetSecurityStatusEnumDescription(itemsec.SecurityStatusfromPLC, out errMessage2);

            if (secInfo != null && !string.IsNullOrEmpty(secInfo.SecResult))
            {
                bpminfo = new BPMSecurityInfo();
                bpminfo.SecInst = secInfo.SecInst;
                bpminfo.SecResult = secInfo.SecResult;
                bpminfo.SecAutograph = tp.TPID.ToString();
                bpminfo.SecurityAdditionalInfo = itemsec.SecurityStatusfromPLC;
                bpminfo.SecMethod = "XRAY";
            }

            return bpminfo;
        }

        public virtual LAP.BaseMessage CreateSortingInstruction(LAP.DestinationRequestMsg drm, List<int> destinations, string IATAcode, string KIDcode, int minScreeningLevel, SortingEdgeData TP, GW.Item item)
        {
            string errMessage = string.Empty;

            LAP.SortInstructionMsg msgOut = new LAP.SortInstructionMsg(LAP.HOST_IDNODE, drm.Envelope.SourceNode, drm.Envelope.MsgProgr, 1);
            msgOut.PrgSSS = drm.PrgSSS;
            msgOut.Cell = drm.Cell;
            msgOut.ObjIDDate = drm.ObjIDDate;
            msgOut.ObjID = drm.ObjID;
            msgOut.LineSeq = drm.LineSeq;
            msgOut.ScannerSeq = drm.ScannerSeq;
            msgOut.SecurityClean = item.bSecurityStatus;
            msgOut.SecurityStatus = item.LastSecurityStatusFromPLC;
            msgOut.Strategy = (short)_sortingDAL.GetTargetsStrategy(TP, destinations, out errMessage);// (short)(TP?.MultipleTargetsStrategy ?? 1);

            if (string.IsNullOrEmpty(IATAcode))
                IATAcode = "0000000000";

            msgOut.IataCode = Encoding.UTF8.GetBytes(IATAcode);
            msgOut.IataCodeString = IATAcode;
            msgOut.ScreeningLevel = (byte)minScreeningLevel;
            //msgOut.Dest = Array.ConvertAll(destinations.ToArray(), input => (short)input);
            //msgOut.Envelope.MsgProgr = uniqueId++;

            for (int j = 0; j < destinations.Count; j++)
                msgOut.Dest[j] = (short)destinations[j];

            return msgOut;
        }

        public virtual LAP.BaseMessage CreateSortingInstruction(LAP.DestinationRequestMsgExt drm, List<int> destinations, string IATAcode, string KIDcode, int minScreeningLevel, SortingEdgeData TP, GW.Item item)
        {
            string errMessage = string.Empty;

            LAP.SortInstructionMsg msgOut = new LAP.SortInstructionMsg(LAP.HOST_IDNODE, drm.Envelope.SourceNode, drm.Envelope.MsgProgr, 1);
            msgOut.PrgSSS = drm.PrgSSS;
            msgOut.Cell = drm.Cell;
            msgOut.ObjIDDate = drm.ObjIDDate;
            msgOut.ObjID = drm.ObjID;
            msgOut.LineSeq = drm.LineSeq;
            msgOut.ScannerSeq = drm.ScannerSeq;
            msgOut.SecurityClean = item.bSecurityStatus;
            msgOut.SecurityStatus = item.LastSecurityStatusFromPLC;
            msgOut.Strategy = (short)_sortingDAL.GetTargetsStrategy(TP, destinations, out errMessage);// (short)(TP?.MultipleTargetsStrategy ?? 1);

            if (string.IsNullOrEmpty(IATAcode))
                IATAcode = "0000000000";

            msgOut.IataCode = Encoding.UTF8.GetBytes(IATAcode);
            msgOut.IataCodeString = IATAcode;
            msgOut.ScreeningLevel = (byte)minScreeningLevel;
            //msgOut.Dest = Array.ConvertAll(destinations.ToArray(), input => (short)input);
            //msgOut.Envelope.MsgProgr = uniqueId++;

            for (int j = 0; j < destinations.Count; j++)
                msgOut.Dest[j] = (short)destinations[j];

            return msgOut;
        }

        public virtual List<SACNotificationAlarm> GetAlarmsStatus(ILogging logger, out string errMessage)
        {
            errMessage = string.Empty;
            return new List<SACNotificationAlarm>();
        }

        public bool ExecuteControlRoomRules(GW.ControlRoomLogConfig conf, out string errMessage)
        {
            errMessage = String.Empty;
            List<GW.ControlRoomLog> controlRoomLogs = new List<GW.ControlRoomLog>();
            DateTime? newLastCheckTS = null;
            try
            {
                string likeCond = string.IsNullOrEmpty(conf.RuleText) ? "%" : conf.RuleText.Replace("*", "%").Replace("?", "_");

                if (conf.Source == "Logbook")
                {
                    List<vwlogbook> logbooks = _sortingDAL.GetLogBookEntries(conf.EventType, likeCond, conf.LastCheckTS, out newLastCheckTS, out errMessage);

                    _LogbookEventTypesLoc ??= _sortingDAL.GetLogBookEventsLoc("de-CH", out errMessage);

                    foreach (vwlogbook l in logbooks)
                    {
                        string eventTypeloc = string.Empty;
                        _LogbookEventTypesLoc?.TryGetValue(l.EventType, out eventTypeloc);

                        object[] pars = JsonConvert.DeserializeObject<object[]>(l.MessageParams);

                        string localizedText = Localizer.Get("de-CH", "LogBookSorting", l.MessageId, pars);
                        if (localizedText.StartsWith("NOTFOUND"))
                            localizedText = Localizer.Get("LogBookSorting", l.MessageId, pars);

                        GW.ControlRoomLog cl = new GW.ControlRoomLog()
                        {
                            EventType = eventTypeloc,
                            Username = l.User,
                            bManual = false,
                            TimestampFrom = l.timestamp,
                            TimestampTo = l.timestamp,
                            Area = null,
                            SystemPart = null,
                            AKZ = null,
                            Cause = null,
                            EventDescription = localizedText,

                            Action = null,
                            bPlanned = false,
                            bOpen = false,
                            bOperationaConsequences = false,
                            BagsLeftBehind = null,
                            bVideo = false,
                            VisaMaintenance = String.Empty,
                            VisaOperations = String.Empty,
                            VisaOthers = String.Empty,
                            Comments = conf.Comment,
                            CreationTimestamp = DateTime.Now
                        };

                        controlRoomLogs.Add(cl);
                    }
                }
                else if (conf.Source == "ChronologicalMessages")
                {
                    List<GW.ChronologicalMessages> chrono = _sortingDAL.GetChronologicalMessages(conf.EventType, likeCond, conf.Duration, conf.LastCheckTS, out newLastCheckTS, out errMessage);

                    _ChronoEventTypesLoc ??= _sortingDAL.GetChronoEventsLoc("de-CH", out errMessage);

                    foreach (GW.ChronologicalMessages l in chrono)
                    {
                        string eventTypeloc = string.Empty;
                        _ChronoEventTypesLoc?.TryGetValue(l.EventType, out eventTypeloc);

                        GW.ControlRoomLog cl = new GW.ControlRoomLog()
                        {
                            EventType = eventTypeloc,
                            Username = l.Username,
                            bManual = false,
                            TimestampFrom = l.TimestampFrom,
                            TimestampTo = l.TImestampTo,

                            Area = l.Area,
                            SystemPart = l.SystemPart,
                            AKZ = l.AKZ,
                            Cause = l.CauseCode,
                            EventDescription = l.EventText,

                            Action = null,
                            bPlanned = false,
                            bOpen = false,
                            bOperationaConsequences = false,
                            BagsLeftBehind = null,
                            bVideo = false,
                            VisaMaintenance = String.Empty,
                            VisaOperations = String.Empty,
                            VisaOthers = String.Empty,
                            Comments = l.Comment,
                            CreationTimestamp = DateTime.Now
                        };
                        
                        controlRoomLogs.Add(cl);
                    }
                }

                if (newLastCheckTS.HasValue)
                    _sortingDAL.UpdateControlRoomLogRulesLastCheck(conf.ID, newLastCheckTS.Value, out errMessage);

                if (controlRoomLogs.Any())
                    _sortingDAL.InsertControlRoomLogs(controlRoomLogs, out errMessage);

                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public virtual bool ManageBSMSpecialCase(GW.Item item, ref SortingEdgeData TP, BSMData BSM, ref List<TargetSortData> SelectedTargets, Common.DataAccess.GW.ITrackingEvents tracker, out bool bSentMES, out bool bSentOCR, out string errMessage)
        {
            errMessage = string.Empty;
            bSentMES = false;
            bSentOCR = false;
            return true;
        }

        public virtual bool ManageSpecialLATECase(GW.Item item, ref SortingEdgeData TP, BSMData BSM, ref List<TargetSortData> SelectedTargets, Common.DataAccess.GW.ITrackingEvents tracker, out bool bSentMES, out bool bSentOCR, out string errMessage)
        {
            errMessage = string.Empty;
            bSentMES = false;
            bSentOCR = false;
            return true;
        }

        public virtual bool HBSTrackingLostReset(GW.Item item, SortingEdgeData TP, DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            return true;
        }

        public virtual void ManageSorterStatusOnChutes(ref LAP.SubsystemStatusMsg ssm)
        {
            return;
        }
    }

    public class SortedEventTypes
    {
        ISortingDAL _sortingDAl = null;

        List<SortEventsTypes> sortedEventTypes = new List<SortEventsTypes>();
        public SortedEventTypes(ISortingDAL sortingDAL)
        {
            _sortingDAl = sortingDAL;
            LoadEvents();
        }
        public int ComponentIndex { get; set; }
        public string PreliminarySortEventType { get; set; }
        public string FinalSortEventType { get; set; }

        public string GetEventType(int idResultType, int componentId)
        {
            string sResult = string.Empty;
            if (sortedEventTypes == null)
                LoadEvents();

            sResult = sortedEventTypes.Where(s => s.ComponentIndex == componentId).Select(s => idResultType == 0 ? s.PreliminarySortEventType : s.FinalSortEventType).FirstOrDefault();

            //if (string.IsNullOrEmpty(sResult))
            //    sResult = "Sorted";

            return sResult;
        }

        public string GetEventTypeByTP(int idResultType, int TPID)
        {
            string sResult = string.Empty;
            if (sortedEventTypes == null)
                LoadEvents();

            sResult = sortedEventTypes.Where(s => s.TrackingPointID == TPID).Select(s => idResultType == 0 ? s.PreliminarySortEventType : s.FinalSortEventType).FirstOrDefault();

            //if (string.IsNullOrEmpty(sResult))
            //    sResult = "Sorted";

            return sResult;
        }

        private void LoadEvents()
        {
            string errMessage = string.Empty;
            sortedEventTypes = _sortingDAl.LoadEventTypes(out errMessage);
        }
    }
}
