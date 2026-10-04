using EasyAirport.Common.DataAccess;
using EasyAirport.Common.DataAccess.DB;
using EasyAirport.Common.DataAccess.GW;
using EasyAirport.Common.Messages;
using EasyAirport.Common.Utilities;
using EasyAirport.Common.Utilities.Log;
using Microsoft.EntityFrameworkCore.Internal;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using System;
using System.Collections.Generic;
using System.Data;
using System.Globalization;
using System.Linq;
using System.Text;

namespace EasyAirport.CDG.BL
{
    public enum CDGSortingMode
    {
        TestSecuriteGlobale = 0,
        Nominal = 1,
        BSMRush = 2,
        MLP = 3,
        PreTri = 4,
        DeposeSortie = 5
    }

    public class SortingCDG : SortingBase
    {
        private ICDGAeroportDAL _cdgDAL;
        public SortingCDG(IConfiguration iconf, ITrackingDAL trackingDAL, IConfigGlobal _GlobalConf, IEBS EBS, IEBSDAL EBSDAL, IBPM BPM, ISortingDAL SortingDAL, ICDGAeroportDAL cdgDAL, IClock clock, ILogging logger, IBaseUtils baseUtils, IServiceScopeFactory scopeFactory)
        : base(iconf, trackingDAL, _GlobalConf, EBS, EBSDAL, BPM, SortingDAL, clock, logger, baseUtils, scopeFactory)
        {
            _cdgDAL = cdgDAL;
        }

        public override bool SpecialFilterFinalTargets(Item item, SortingEdgeData TP, ref List<TargetSortData> FinalSorts, Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = "";
            return true;
        }

        private bool addTrackingBPM(Item item, ItemSecurity iSec)
        {
            bool bRet;
            string errMessage = string.Empty;

            if (!iSec.ComponentIndex.HasValue)
                return true;

            TrackingPoint tp = _trackingDAL.GetTPfromComponent(iSec.ComponentIndex.Value);

            BPMSecurityInfo bpmsecinfo = GetBPMSecurityInfo(tp, iSec, out errMessage);

            bool BPMPending;
            bRet = _BPM.CreateBPMForComponent("Security", item, 0, iSec.ComponentIndex, iSec.ComponentIndex.Value, null, bpmsecinfo, DateTime.Now, out BPMPending, out errMessage);

            int TPid = tp != null ? tp.ID : 0;
            string TPSorter = tp != null ? tp.TPSorter : string.Empty;

            _trackingDAL.AddBagTracking(BagTrackingEventType.XRay, item.BagId,
                                        item.LastVid, item.ControlId, item.Flight, item.SDT, TPSorter, item.CellId, iSec.ComponentIndex, TPid, _sortingDAL.ConvertScreeningResultToDescription(iSec.SecurityStatusfromPLC), out errMessage, "XRay", iSec.ComponentIndex.Value);
            return BPMPending;
        }

        public override string CleanSecurityStatus()
        {
            return "12";
        }

        public override string Level1Clean()
        {
            return "12";
        }

        public override string OSSClean()
        {
            return "02";
        }

        public override BPMSecurityInfo GetBPMSecurityInfo(TrackingPoint tp, ItemSecurity itemsec, out string errMessage)
        {
            errMessage = String.Empty;
            string errMessage2 = string.Empty;
            SecurityStatusEnumDescription secInfo = _sortingDAL.GetSecurityStatusEnumDescription(itemsec.SecurityStatusfromPLC, out errMessage2);

            if (secInfo == null)
                return null;

            SecurityStatusLookup sec = _trackingDAL.GetSecurityStatusLookup(itemsec.SecurityStatusfromPLC, out errMessage2);
            return new BPMSecurityInfo
            {
                SecInst = secInfo.SecInst,
                SecResult = secInfo.SecResult,
                SecAutograph = tp.TPID.ToString(),
                SecurityAdditionalInfo = sec.BPMStatus,
                SecMethod = "AT"
            };
        }

        public override bool ReturnOCR()
        {
            return false;
        }

        public override List<int> CalculateOCRDestinations(string Caller, Item item, LAP.BaseMessage destReq, EasyAirport.Common.DataAccess.GW.ITrackingEvents tracker, out string IATAcode, out string ControlId, out SortingEdgeData TPOut, out string errMessage)
        {
            IATAcode = string.Empty;
            ControlId = string.Empty;
            TPOut = null;
            errMessage = string.Empty;
            return null;
        }

        private void CDGPreCalculateDestinations(Item item, LAP.BaseMessage destReq, EasyAirport.Common.DataAccess.GW.ITrackingEvents tracker, int sortingMode, out string errMessage)
        { 
            errMessage = string.Empty;

            var ATRafterDeposeIxs = new List<int>()
            {
                7111,
                7121,
                7131,
                7141,
                7151,
                7161,
            };
            // checks that it is the first time seeing the item in the system after reintroduction from CU
            if (item.ItemReconciledId.HasValue && item.LastSecurityStatusFromPLC == "42" && ATRafterDeposeIxs.Contains(destReq.ComponentIndex)) 
            {
                ItemSecurity lastItemSecurity = null;
                Item iReconc = _trackingDAL.GetItemByID(item.ItemReconciledId.Value, out string errMsg);
                lastItemSecurity = _trackingDAL.GetItemLastSecurity(iReconc, out string errMsg2);
                if (lastItemSecurity != null)
                {
                    int ReintroductionTimeout = (int)(GlobalConf["SEC_REINTRODUCTION_TIMEOUT"] ?? 10);
                    // Check reintroduction timeout from L4
                    if ((DateTime.Now - lastItemSecurity.timestamp).TotalMinutes > ReintroductionTimeout)
                    {
                        // Reset item security if item is first seen after reintroduction and the timeout has expired
                        bool bRet = _trackingDAL.ResetItemSecurity(item, out errMessage);
                        tracker.AddTimingStep("Reset Item Security for reintroduction timeout");

                        if (!bRet)
                            tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", errMessage);
                        item.bSecurityStatus = false;
                        item.LastSecurityStatusFromPLC = null;
                        tracker.WriteMessage(TrackingLevel.Info, "HBSExpire");
                    }
                }
            }

            var ATRSorterAndIntroIndexes = new List<int>()
            {
                7951,
                7952,
                7362,
                7363,
                7364,
                7365,
                7366,
                7367,
            };

            //this is used in case a bag is recognized at the MES to not use the next barcodes read from the intros and sorters ATRs
            //and still use the barcode provided by the MES.
            //the customer sees the intros and sorters ATRs to be used only for reconciliation.
            if (item.bCodedByMES == true && ATRSorterAndIntroIndexes.Contains(destReq.ComponentIndex) && item.BagId != null && !item.BagId.Contains(","))
            {
                _sortingDAL.RemoveItemBarcodesByComponent(item, destReq.ComponentIndex);
            }

            bool SpecialPriorityOverMes = (bool)(GlobalConf["SPECIAL_PRIORITY_OVER_MES"] ?? false);
            // SPECIAL_PRIORITY_OVER_MES deve essere a true durante il TSG, false altrimenti
            if (SpecialPriorityOverMes != (sortingMode == (int)CDGSortingMode.TestSecuriteGlobale || sortingMode == (int)CDGSortingMode.DeposeSortie))
            {
                _sortingDAL.SetGlobalConfig("SPECIAL_PRIORITY_OVER_MES", (sortingMode == (int)CDGSortingMode.TestSecuriteGlobale || sortingMode == (int)CDGSortingMode.DeposeSortie) ? "1" : "0", out errMessage);
                GlobalConf.Refresh();
            }
        }

        private void CDGPostCalculateDestinations(Item item, LAP.BaseMessage destReq, int sortingMode, ref List<int> res)
        {
            if (sortingMode == (int)CDGSortingMode.PreTri && res.Count() > 1)
            {
                //invert positions of OSS and EDS destinations
                var oss = new List<int>();
                oss.Add(res.IndexOf(9212));
                oss.Add(res.IndexOf(9222));
                oss.Add(res.IndexOf(9232));
                oss.Add(res.IndexOf(9242));
                var ossIndex = oss.Max();

                if (ossIndex != -1)
                {
                    var eds = new List<int>();
                    eds.Add(res.IndexOf(9211));
                    eds.Add(res.IndexOf(9221));
                    eds.Add(res.IndexOf(9231));
                    eds.Add(res.IndexOf(9241));
                    var edsIndex = eds.Max();

                    if (edsIndex != -1)
                    {
                        //swap values
                        var temp = res[ossIndex];
                        res[ossIndex] = res[edsIndex];
                        res[edsIndex] = temp;
                    }
                }
            }

            if (sortingMode == (int)CDGSortingMode.BSMRush && res.Any(e => (e > 9310 && e < 9315) || (e > 9320 && e < 9325)))
            {
                //removes EDS destination if at least one MES is present
                res.Remove(9211);
                res.Remove(9212);

                res.Remove(9221);
                res.Remove(9222);

                res.Remove(9231);
                res.Remove(9232);

                res.Remove(9241);
                res.Remove(9242);
            }

            var postEDS = new List<int>() { 7313, 7323, 7333, 7343 };

            if (item.LastSecurityStatusFromPLC != "13" && postEDS.Contains(destReq.ComponentIndex))
            {
                res.Clear();
                int destination = 0;
                switch (destReq.ComponentIndex)
                {
                    case 7313: destination = 9211; break;
                    case 7323: destination = 9221; break;
                    case 7333: destination = 9231; break;
                    case 7343: destination = 9241; break;
                }
                res.Add(destination);
            }
        }

        public override List<int> CalculateDestinations(string Caller, Item item, LAP.BaseMessage destReq, EasyAirport.Common.DataAccess.GW.ITrackingEvents tracker, out string IATAcode, out string ControlId, out SortingEdgeData TPOut, out string errMessage)
        {
            GlobalConf.Refresh();
            int sortingMode = (int)(GlobalConf["SORTING_MODE"] ?? (int)CDGSortingMode.Nominal);

            CDGPreCalculateDestinations(item, destReq, tracker, sortingMode, out errMessage);

            List<int> res = base.CalculateDestinations(Caller, item, destReq, tracker, out IATAcode, out ControlId, out TPOut, out errMessage);

            CDGPostCalculateDestinations(item, destReq, sortingMode, ref res);

            return res;
        }

        public override bool GetSecurityTargets(SortingEdgeData TP, EasyAirport.Common.DataAccess.GW.ITrackingEvents tracker, Item item, LAP.BaseMessage destReq, FlightData Flight, BSMData BSM, ref List<TargetSortData> SelectedTargets, ref List<TargetSortData> FinalTargets, ref List<TargetSortData> EBSTargets, out string errMessage)
        {
            errMessage = string.Empty;
            int sortingMode = (int)(GlobalConf["SORTING_MODE"] ?? (int)CDGSortingMode.Nominal);
            int HBSTimeoutL2 = (int)(GlobalConf["HBS_TIMEOUT_L2"] ?? 60);
            int HBSTimeoutL3 = (int)(GlobalConf["HBS_TIMEOUT_L3"] ?? 120);
            bool HBSEmergency = (bool)(GlobalConf["HBS_EMERGENCY"] ?? false);
            string HBSEmergencyMode = (string)(GlobalConf["HBS_EMERGENCY_MODE"]);
            int? timeout = null;
            int? nextStatus = null;
            TimeSpan elapsed = TimeSpan.FromSeconds(0);

            try
            {
                //if mode is pre-tri, avoid EDS, so do not modify the previously calculated targets
                if (sortingMode == (int)CDGSortingMode.PreTri)
                    return true;

                if (HBSEmergency && HBSEmergencyMode == "HBS_EMERGENCY_SPECIAL")
                {
                    FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, "HBSEMERGENCY", out errMessage);
                    tracker.WriteMessage(TrackingLevel.Info, "ItemSecurity", "HBS_EMERGENCY_SPECIAL", string.Join(",", SelectedTargets.Select(t => t.Target)));
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EX_MESSAGE", "GetSpecialSort-HBSEMERGENCY", errMessage);
                    return true;
                }

                ItemSecurity lastItemSecurity = null;
                if (item.ItemSecurity.Any())
                    lastItemSecurity = item.ItemSecurity.OrderByDescending(isec => isec.timestamp).Take(1).FirstOrDefault();
                else if (item.ItemReconciledId.HasValue)
                {
                    Item iReconc = _trackingDAL.GetItemByID(item.ItemReconciledId.Value, out string errMsg);
                    lastItemSecurity = _trackingDAL.GetItemLastSecurity(iReconc, out string errMsg2);
                }
                
                if (TP.Context == "I")
                {
                    // Per oggetti il cui stato di sicurezza non è noto
                    if ((item.LastSecurityStatusFromPLC == null) || (item.LastSecurityStatusFromPLC == ""))
                    {
                        SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRay", out errMessage);
                        if (!SelectedTargets.Any())
                        {
                            SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                            foreach (var target in SelectedTargets)
                            {
                                target.SortingReason = "SECURITY";
                                target.SortingReasonSpecial = "L4";
                            }
                        }
                    }
                    // Oggetti che hanno uno stato di sicurezza impostato 
                    else
                    {
                        //caso speciale contaminati
                        if (item.LastSecurityStatusFromPLC == "109")
                        {
                            SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                            foreach (var target in SelectedTargets)
                            {
                                target.SortingReason = "SECURITY";
                                target.SortingReasonSpecial = "L4";
                            }
                            if (SelectedTargets.Any())
                            {
                                FinalTargets = new List<TargetSortData>();
                                return true;
                            }
                        }

                        SecurityStatusLookup sec = _trackingDAL.GetSecurityStatusLookup(item.LastSecurityStatusFromPLC, out errMessage);
                        // Oggetti che devono ripetere il controllo di livello 1
                        if (sec.NextSecurityLevel == 1)
                        {
                            int recCount = _trackingDAL.GetSecurityRecirculationCount(item, 1, out errMessage);
                            if (!sec.RecirculationCount.HasValue || (recCount <= sec.RecirculationCount))
                                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRay", out errMessage);
                            if (!SelectedTargets.Any())
                            {
                                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                                foreach (var target in SelectedTargets)
                                {
                                    target.SortingReason = "SECURITY";
                                    target.SortingReasonSpecial = "L4";
                                }
                            }
                        }
                        // Oggetti in attesa di esito di livello 2 o 3
                        else if ((sec.NextSecurityLevel == 2) || (sec.NextSecurityLevel == 3))
                            SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                        // Oggetto dichiarato sporco senza appello
                        else if (sec.NextSecurityLevel >= 4)
                        {
                            // Invio a uscita verso Livello 4
                            SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                            foreach (var target in SelectedTargets)
                            {
                                target.SortingReason = "SECURITY";
                                target.SortingReasonSpecial = "L4";
                            }
                            // Se non esiste path verso L4 (prima di EDS)
                            // Caso di bagaglio OSS non coperto perchè non entra in questa funzione
                            if (!SelectedTargets.Any())
                                return true;
                        }
                    }
                }
                else if (TP.Context == "F")
                {
                    // Per oggetti il cui stato di sicurezza non è noto
                    if ((item.LastSecurityStatusFromPLC == null) || (item.LastSecurityStatusFromPLC == ""))
                    {
                        int RecThresholdReconcile = (int)(GlobalConf["REC_THRESHOLD_RECONCILE"] ?? 3);

                        //bags without security status, without bagid and within the "reconcile recirculation threshold"
                        //will not receive destinations and will recirculate to try reconcile again
                        if (string.IsNullOrEmpty(item.BagId) && item.RecirculationCounter <= RecThresholdReconcile)
                        {
                            FinalTargets = new List<TargetSortData>();
                            SelectedTargets = new List<TargetSortData>();
                            return true;
                        }

                        // Invio a uscita verso Livello 4
                        SelectedTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.XRayL4, out errMessage);

                        if (!SelectedTargets.Any())
                            SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);

                        foreach (var target in SelectedTargets)
                        {
                            target.SortingReason = "SECURITY";
                            target.SortingReasonSpecial = "L4";
                        }
                    }
                    // Oggetti che hanno uno stato di sicurezza impostato 
                    else
                    {
                        SecurityStatusLookup sec = _trackingDAL.GetSecurityStatusLookup(item.LastSecurityStatusFromPLC, out errMessage);
                        // Gestione timeout SAC
                        if (item.LastSecurityStatusFromPLC != null && !(sec.CalculateSorting ?? false))
                        {
                            if (sec.NextSecurityLevel == 2)
                            {
                                timeout = HBSTimeoutL2;
                                elapsed = DateTime.Now - lastItemSecurity.timestamp;
                                nextStatus = 28;
                            }
                            else if (sec.NextSecurityLevel == 3)
                            {
                                timeout = HBSTimeoutL3;
                                elapsed = DateTime.Now - lastItemSecurity.timestamp;
                                nextStatus = 38;
                            }

                            if (timeout.HasValue && elapsed.TotalSeconds > timeout)
                            {
                                ItemSecurity isec = new ItemSecurity();
                                isec.IDItem = item.ID;
                                isec.SecurityStatusfromPLC = nextStatus.ToString();
                                isec.timestamp = _clock.Now;
                                isec.ComponentIndex = lastItemSecurity.ComponentIndex;
                                isec.bSecurityStatus = false;
                                isec.VID = item.LastVid;

                                _trackingDAL.SetItemSecurity(item, isec, out errMessage);
                                bool BPMCreated = addTrackingBPM(item, isec);

                                isec.BPMPending = !BPMCreated;

                                tracker.WriteMessage(TrackingLevel.Info, "NewSecurityStatus", isec.SecurityStatusfromPLC);

                                sec = _trackingDAL.GetSecurityStatusLookup(nextStatus.ToString(), out errMessage);
                                if (!string.IsNullOrEmpty(errMessage))
                                    tracker.WriteMessage(TrackingLevel.Error, "EX_MESSAGE", "GetSecurityStatusLookup", errMessage);

                            }
                        }

                        // Oggetti che devono ripetere il controllo di livello 1                           
                        if (sec.NextSecurityLevel == 1)
                        {
                            // Invio a uscita verso Livello 4
                            SelectedTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.XRayL4, out errMessage);

                            if (!SelectedTargets.Any())
                                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                        }
                        // Oggetti in attesa di esito di livello 2 o 3                            
                        else if ((sec.NextSecurityLevel == 2) || (sec.NextSecurityLevel == 3))
                        {
                            // Attesa su sorter
                            SelectedTargets.Clear();
                            FinalTargets.Clear();
                        }
                        // Oggetto dichiarato sporco senza appello in TSG
                        else if ((sec.NextSecurityLevel >= 4) && (sortingMode == (int)CDGSortingMode.TestSecuriteGlobale))
                        {
                            // Invio a uscita verso Livello 4
                            var trgSuspect = _sortingDAL.GetSortingTargetByName((string)(GlobalConf["TSG_SUSPECT_TARGET"] ?? ""), out errMessage);
                            int targetSuspect = trgSuspect != null ? trgSuspect.componentindex : 44;
                            SelectedTargets = new List<TargetSortData>() { new TargetSortData() { Target = targetSuspect, TargetType = "FINAL", bOverridable = false } };
                        }
                        // Oggetto dichiarato sporco senza appello
                        else if (sec.NextSecurityLevel >= 4)
                        {
                            // Invio a uscita verso Livello 4
                            SelectedTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.XRayL4, out errMessage);

                            if (!SelectedTargets.Any())
                                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRayL4", out errMessage);
                            foreach (var target in SelectedTargets)
                            {
                                target.SortingReason = "SECURITY";
                                target.SortingReasonSpecial = "L4";
                            }
                        }
                    }
                }

                FinalTargets = new List<TargetSortData>();
                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public override int? CalculateOSS(BSMData BSM, EasyAirport.Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;

            bool useOSS = (bool)(GlobalConf["OSS_ENABLED"] ?? true); //if does not exist gets true as default
            if (!useOSS)
                return null;

            configAirports airportORG = null;
            configAirports airportDES = null;
            configAirlines airlineORG = null;
            configAirlines airlineDES = null;
            int? minLevel = null;
            try
            {
                if (!string.IsNullOrEmpty(BSM.Origin_Airport) &&
                    !string.IsNullOrEmpty(BSM.DES) &&
                    !string.IsNullOrEmpty(BSM.InboundFlight) &&
                    !string.IsNullOrEmpty(BSM.Flight))
                {
                    string FLC;
                    int FLN;
                    string FLX;
                    string flightINB = FlightUtils.ParseAndFormatFlight(BSM.InboundFlight, 4, out FLC, out FLN, out FLX, true);
                    airportORG = _sortingDAL.GetAirport(BSM.Origin_Airport, out errMessage);
                    airportDES = _sortingDAL.GetAirport(BSM.DES, out errMessage);
                    airlineORG = _sortingDAL.GetAirline(FLC, out errMessage);
                    airlineDES = _sortingDAL.GetAirline(BSM.FLC, out errMessage);
                    if (airportORG != null && airportDES != null &&
                        airlineDES != null && airlineORG != null &&
                        airportORG.SecurityModeOrigin == "O" &&
                        airportDES.SecurityModeDestination == "O" &&
                        airlineORG.SecurityModeIn == "O" &&
                        airlineDES.SecurityMode == "O")
                    {
                        tracker.WriteMessage(TrackingLevel.Info, "CalculateOSS", "Airline+Airport-ORG+DES", airportORG.SecurityModeOrigin, (int)ScreeningLevelRequired.OSS);
                        minLevel = (int)ScreeningLevelRequired.OSS;
                    }
                }
                return minLevel;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public override List<SACNotificationAlarm> GetAlarmsStatus(ILogging logger, out string errMessage)
        {
            errMessage = string.Empty;
            StringBuilder sb = new StringBuilder(string.Empty);
            SACNotificationAlarm alm = null;
            GlobalConf.Refresh();
            List<SACNotificationAlarm> currAlms = _baseUtils.GetCurrentAlarmStatus(out errMessage);
            if (!string.IsNullOrEmpty(errMessage))
                sb.AppendLine(errMessage);

            logger.WriteLog("General", Severity.Debug, "generalLog", $"Alams:{currAlms.Count}");

            List<SACNotificationAlarm> newAlms = new List<SACNotificationAlarm>();

            alm = currAlms.Where(a => a.AlarmID == "AODBConnection").FirstOrDefault();
            logger.WriteLog("General", Severity.Debug, "generalLog", $"Alarm: {alm.AlarmID} {alm.State}");

            if (alm != null && alm.State != "DISABLED")
            {
                bool newstatus = _cdgDAL.GetSafirConnectionAlarm("SafirFlg", 60);
                SACNotificationAlarm newAlm = new SACNotificationAlarm() { AlarmID = alm.AlarmID, State = newstatus ? "ON" : "OFF" };
                logger.WriteLog("General", Severity.Debug, "generalLog", $"NewAlarm: {newAlm.AlarmID} {newAlm.State}");
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }

            alm = currAlms.Where(a => a.AlarmID == "BagMessageConnection").FirstOrDefault();

            if (alm != null && alm.State != "DISABLED")
            {
                bool newstatus = _cdgDAL.GetSafirConnectionAlarm("SafirBag", 60);
                SACNotificationAlarm newAlm = new SACNotificationAlarm() { AlarmID = alm.AlarmID, State = newstatus ? "ON" : "OFF" };
                logger.WriteLog("General", Severity.Debug, "generalLog", $"NewAlarm: {newAlm.AlarmID} {newAlm.State}");
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }

            alm = currAlms.Where(a => a.AlarmID == "NoBSM").FirstOrDefault();

            if (alm != null && alm.State != "DISABLED")
            {
                int noBsmCount = (int)(GlobalConf["NOBSM_CHECK"] ?? 0);
                int noBsmTimeWin = (int)(GlobalConf["NOBSM_CHECK_TIMEWINDOW"] ?? 0);

                SACNotificationAlarm newAlm = _sortingDAL.GetNoBSMAlarmBSMCheck(out errMessage);
                if (!string.IsNullOrEmpty(errMessage))
                    sb.AppendLine(errMessage);
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }


            alm = currAlms.Where(a => a.AlarmID == "NoFlight").FirstOrDefault();

            if (alm != null && alm.State != "DISABLED")
            {
                int noFlightCount = (int)(GlobalConf["NOFLIGHT"] ?? 0);
                int? noFlightTimeWin = (int?)(GlobalConf["NOFLIGHT_TIME"]);

                SACNotificationAlarm newAlm = _sortingDAL.GetNoFlightAlarm(noFlightTimeWin, noFlightCount, out errMessage);
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }


            alm = currAlms.Where(a => a.AlarmID == "NoAllocation").FirstOrDefault();

            if (alm != null && alm.State != "DISABLED")
            {
                int noAllocCount = (int)(GlobalConf["NOALLOCATION_ALERT"] ?? 0);
                int? noAllocTimeWin = (int?)(GlobalConf["NOALLOCATION_TIME"]);

                SACNotificationAlarm newAlm = _sortingDAL.GetNoAllocationAlarm(noAllocTimeWin, noAllocCount, out errMessage);
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }

            alm = currAlms.Where(a => a.AlarmID == "RecirculationError").FirstOrDefault();

            if (alm != null && alm.State != "DISABLED")
            {
                int MaxRecircBags = (int)(GlobalConf["SORTER_MAX_RECIRCULATIONS_ALERT"] ?? 0);
                int? MaxRecircTime = (int?)(GlobalConf["SORTER_MAX_RECIRCULATIONS_TIME"]);

                SACNotificationAlarm newAlm = _sortingDAL.GetItemRecirculationSorterAlarm(MaxRecircTime, MaxRecircBags, out errMessage);
                if (!string.IsNullOrEmpty(errMessage))
                    sb.AppendLine(errMessage);
                if (alm.State != newAlm.State)
                    newAlms.Add(newAlm);
            }

            errMessage = sb.ToString();
            return newAlms;
        }

        private bool IsBeforeEDS(int ComponentIndex, out string errMessage)
        {
            errMessage = "";
            var c = _trackingDAL.GetComponentByID(ComponentIndex, out errMessage);
            if (c == null)
            {
                errMessage = "No component found";
                return false;
            }
            string[] monteCP = {"CP111ST000",
                                "CP111PS101",
                                "CP111ST001",
                                "CP112CN002",
                                "CP112ST003",
                                "CP112ST004",
                                "CP112ST005",
                                "CP131ST000",
                                "CP131ST001",
                                "CP131ST002",
                                "CP211ST000",
                                "CP211PS101",
                                "CP211ST001",
                                "CP212CN002",
                                "CP212ST003",
                                "CP212ST004",
                                "CP231ST000",
                                "CP231ST001",
                                "CP231ST002",
                                "CP231ST003",
                                "CP231ST004",
                                "CP311ST000",
                                "CP311PS101",
                                "CP311ST001",
                                "CP312CN002",
                                "CP312ST003",
                                "CP312ST004",
                                "CP331ST000",
                                "CP331ST001",
                                "CP331ST002",
                                "CP331ST003",
                                "CP331ST004",
                                "CP411PS100",
                                "CP411ST000",
                                "CP412CN001",
                                "CP412ST002",
                                "CP412ST003",
                                "CP412ST004",
                                "CP431ST000",
                                "CP431ST001",
                                "CP431ST002" };

            return monteCP.Contains(c.Name) || c.Name.StartsWith("DC") || c.Name.StartsWith("NL");
        }

        public override SecurityStatusLookup ManageScreeningResult(Item item, LAP.ScreeningResultMsg res, Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                // get security translation
                SecurityStatusLookup secval = _trackingDAL.GetSecurityStatusLookup(res.ScreeningResultDataString, out errMessage);

                if (secval == null)
                    return null;

                if(item.LastSecurityStatusFromPLC != null && item.LastSecurityStatusFromPLC != "13")
                {
                    // get current security translation
                    SecurityStatusLookup secItem = _trackingDAL.GetSecurityStatusLookup(item.LastSecurityStatusFromPLC, out errMessage);

                    //contamination does not pass from this function so no worries
                    if (secItem != null && secItem.SecurityLevel >= secval.SecurityLevel)
                    {
                        tracker.WriteMessage(TrackingLevel.Info, "GENERIC", $"New security status {res.ScreeningResultDataString} ignored due to lower level compared to current {item.LastSecurityStatusFromPLC}");
                        return null;
                    }
                }

                ItemSecurity itemsec = new ItemSecurity();
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

        public override bool SetContamination(Item item, LAP.TrackingEventNotificationMsg ten, Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            errMessage = string.Empty;
            string TPName = string.Empty;

            var secString = IsBeforeEDS(ten.ComponentIndex, out errMessage) ? "09" : "109";
            if (!string.IsNullOrEmpty(errMessage))
                return false;

            try
            {
                // get security translation
                SecurityStatusLookup secval = _trackingDAL.GetSecurityStatusLookup(secString, out errMessage);

                if (secval == null)
                    return false;

                ItemSecurity itemsec = new ItemSecurity();
                itemsec.IDItem = item.ID;
                itemsec.SecurityStatusfromPLC = secString;
                itemsec.ComponentIndex = ten.ComponentIndex;
                itemsec.timestamp = DateTime.Now;
                itemsec.bSecurityStatus = secval.bSecurityStatus;
                itemsec.VID = ten.VID;

                bool bRet = _trackingDAL.SetItemSecurity(item, itemsec, out errMessage);
                if (!bRet)
                    return false;

                item.MinimumScreeningLevel = (int)ScreeningLevelRequired.Auto;

                // Impostazione MinimumScreeningLevel a HighRisk per inibire il calcolo dell'eventuale OSS
                if (secString == "109")
                    item.MinimumScreeningLevel = (int)ScreeningLevelRequired.HiRisk;

                _trackingDAL.SetMinimumScreeningLevel(item, (ScreeningLevelRequired)item.MinimumScreeningLevel, out errMessage);

                bool BPMPending;
                bRet = _BPM.CreateBPMForComponent("Security", item, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, null, DateTime.Now, out BPMPending, out errMessage);
                itemsec.BPMPending = BPMPending;

                int? TPid = _trackingDAL.TPfromComponent(ten.TrackingPoint);
                TrackingPoint tp = _trackingDAL.GetTPfromComponent(ten.ComponentIndex);

                if (string.IsNullOrEmpty(TPName))
                    TPName = tp.TPName;
                if (string.IsNullOrEmpty(TPName))
                    TPName = ten.TrackingPoint.ToString();

                _trackingDAL.AddBagTracking(BagTrackingEventType.Contaminated, item.BagId, item.LastVid, item.ControlId,
                                            item.Flight, item.SDT, string.Empty, item.CellId, string.Empty,
                                            TPid, _sortingDAL.ConvertScreeningResultToDescription(secString), ten.TrackingEvent * 100 + ten.Reason, out errMessage, "Contamination", TPName);

                for (int i = 0; i < ten.N_Obj; i++)
                {
                    long vidContamined = 0;
                    switch (i)
                    {
                        case 0: vidContamined = (long)ten.ObjIDDate1 * 1000000000 + (long)ten.ObjID1; break;
                        case 1: vidContamined = (long)ten.ObjIDDate2 * 1000000000 + (long)ten.ObjID2; break;
                        case 2: vidContamined = (long)ten.ObjIDDate3 * 1000000000 + (long)ten.ObjID3; break;
                    }

                    Item itemContamined = _trackingDAL.GetItemByLastVID(vidContamined, out errMessage);
                    if (itemContamined == null)
                    {
                        //no Item found in DB
                        return false;
                    }

                    itemsec.IDItem = itemContamined.ID;
                    bRet = _trackingDAL.SetItemSecurity(itemContamined, itemsec, out errMessage);
                    if (!bRet)
                        return false;

                    itemContamined.MinimumScreeningLevel = (int)ScreeningLevelRequired.Auto;
                    // Impostazione MinimumScreeningLevel a HighRisk per inibire il calcolo dell'eventuale OSS
                    if (secString == "109") {
                        itemContamined.MinimumScreeningLevel = (int)ScreeningLevelRequired.HiRisk;                       
                    }
                    _trackingDAL.SetMinimumScreeningLevel(itemContamined, (ScreeningLevelRequired)item.MinimumScreeningLevel, out errMessage);

                    bRet = _BPM.CreateBPMForComponent("Security", itemContamined, ten.Envelope.SourceNode, ten.ComponentIndex, ten.ComponentIndex, null, null, DateTime.Now, out BPMPending, out errMessage);

                    _trackingDAL.AddBagTracking(BagTrackingEventType.Contaminated, itemContamined.BagId, itemContamined.LastVid, itemContamined.ControlId,
                                           itemContamined.Flight, itemContamined.SDT, string.Empty, itemContamined.CellId, string.Empty,
                                           TPid, _sortingDAL.ConvertScreeningResultToDescription(secString), ten.TrackingEvent * 100 + ten.Reason, out errMessage, "Contamination", TPName);
                }
                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public override bool SetTrackingEvent(Item item, LAP.TrackingEventNotificationMsg trackingEvent, Common.DataAccess.GW.ITrackingEvents trackEvents, out bool bFinalTarget, out TrackingPoint TP, out string errMessage)
        {
            errMessage = string.Empty;
            bFinalTarget = false;
            trackEvents.SetItem(item);
            string TPName = string.Empty;

            ItemResult ir = new ItemResult(trackingEvent);
            ir.IDItem = item.ID;
            TP = null;

            string sorterEnd = string.Empty;
            int? TPid = _trackingDAL.TPfromComponent(trackingEvent.TrackingPoint);
            if (TPid.HasValue)
            {
                SortingEdgeData TPend = _trackingDAL.GetTPbyEndID(TPid.Value);
                sorterEnd = TPend?.TPSorterEnd ?? string.Empty;
                TPName = TPend?.EdgeEndNameID ?? string.Empty;
            }

            if (trackingEvent.TrackingEvent == 2) // Contaminated)
                trackEvents.WriteMessage(TrackingLevel.Info, "Contaminated");
            else if (trackingEvent.TrackingEvent == 4) // TrackingLost
            {
                trackEvents.WriteMessage(TrackingLevel.Info, "TrackingLost");
                if (string.IsNullOrEmpty(TPName))
                    TPName = _trackingDAL.GetTrackingLostLocationName(trackingEvent.TrackingPoint, out errMessage);
                if (string.IsNullOrEmpty(TPName))
                    TPName = trackingEvent.TrackingPoint.ToString();

                _trackingDAL.AddBagTracking(BagTrackingEventType.TrackingLost, item.BagId,
                    item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, item.CellId, trackingEvent.TrackingPoint.ToString(), TPid, string.Empty, 400 + trackingEvent.Reason, out errMessage, "TrackingLost", trackingEvent.TrackingEvent, trackingEvent.Reason, TPName);
            }
            else if (trackingEvent.TrackingEvent == 5) // Appeared)
            {
                if (string.IsNullOrEmpty(TPName))
                    TPName = _trackingDAL.GetTrackingLostLocationName(trackingEvent.TrackingPoint, out errMessage);
                if (string.IsNullOrEmpty(TPName))
                    TPName = trackingEvent.TrackingPoint.ToString();

                // Default event Appeared
                _trackingDAL.AddBagTracking(BagTrackingEventType.Appeared, item.BagId,
                    item.LastVid, item.ControlId, item.Flight, item.SDT, sorterEnd, item.CellId, trackingEvent.TrackingPoint.ToString(), TPid, string.Empty, 500 + trackingEvent.Reason, out errMessage, "Appeared", trackingEvent.TrackingEvent, trackingEvent.Reason);

                trackEvents.WriteMessage(TrackingLevel.Info, "Appeared");
            }

            bool breturn = _trackingDAL.SetItemResult(ir, out errMessage);
            if (!breturn)
                trackEvents.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemResult", errMessage);
            trackEvents.WriteMessage(TrackingLevel.Debug, "GENERIC", $"After SetItemResult");

            return breturn;
        }

        void CloneTarget(List<TargetSortData> targets, int index, int alt)
        {
            TargetSortData tsd = new TargetSortData();
            tsd.IDSnapshot = targets[0].IDSnapshot;
            tsd.TargetType = targets[0].TargetType;
            tsd.Sorter = targets[0].Sorter;
            tsd.Target = alt;
            tsd.TargetTPid = targets[0].TargetTPid; ////???
            tsd.Weight = targets[0].Weight;
            tsd.bOverridable = targets[0].bOverridable;
            tsd.OutputRule = targets[0].OutputRule;
            tsd.SortingReason = targets[0].SortingReason;
            tsd.SortingReasonSpecial = targets[0].SortingReasonSpecial;
            tsd.AlternativeTarget1 = targets[0].AlternativeTarget1;
            tsd.AlternativeTarget2 = targets[0].AlternativeTarget2;
            tsd.SortingStrategy = targets[0].SortingStrategy;
            targets.Add(tsd);
        }

        public override List<TargetSortData> ManageRecirculations(SortingEdgeData TP, Common.DataAccess.GW.Item i, List<TargetSortData> targets, Common.DataAccess.GW.ITrackingEvents tracker, out string errMessage)
        {
            GlobalConf.Refresh();
            int RecThreshold1 = (int)(GlobalConf["REC_THRESHOLD1"] ?? 10);
            int RecThreshold2 = (int)(GlobalConf["REC_THRESHOLD2"] ?? 100);

            int RecThresholdRush = (int)(GlobalConf["REC_THRESHOLD_RUSH"] ?? 3);
            int RecThresholdPreTri = (int)(GlobalConf["REC_THRESHOLD_PRETRI"] ?? 3);
            int SortingMode = (int)(GlobalConf["SORTING_MODE"] ?? 1);

            errMessage = string.Empty;

            if (SortingMode == (int)CDGSortingMode.TestSecuriteGlobale)
                return targets;

            try
            {
                List<RecirculationCount> recCount = _sortingDAL.GetItemRecirculationCount(TP.PLCNodeStart.Value, i, out errMessage);

                if (!recCount.Any() && SortingMode != (int)CDGSortingMode.BSMRush)
                    return targets;

                // Chute with unlimited recirculation (CYNO/ULTIME)
                // Use targets by GetSecurityTargets
                if (targets.Any() && targets[0].SortingReason == "SECURITY" && targets[0].SortingReasonSpecial == "L4")
                    return targets;

                int maxRecirc = recCount.Count() > 0 ? recCount.Max(t => t.cnt) : 0;

                //continue recirculation and return
                if (SortingMode == (int)CDGSortingMode.PreTri && maxRecirc <= RecThresholdPreTri)
                    return targets;

                string BSMException = null;
                BSMData BSM = null;

                if (i.idBSM != null)
                {
                    BSM = _sortingDAL.GetBSM(i.idBSM.Value, out errMessage);
                    BSMException = BSM?.Exception;
                }

                if (BSMException == null)
                    BSMException = "";

                //if in mode Crise Rush and bag has missed the flight
                if (SortingMode == (int)CDGSortingMode.BSMRush
                    && (TP.ComponentIndexStart==7951 || TP.ComponentIndexStart == 7952)
                    && targets.Any()
                    && (i.BagDestinationStatus == "Late"
                        || _cdgDAL.CheckIfFlightDepartureGone(i.Flight, i.SDT)
                        || ((BSMException.Contains("RUSH") || BSMException.Contains("RRTE"))
                            && !_cdgDAL.CheckIfBagRelabeled(i.BagId, false)
                            && BSM != null
                            && _cdgDAL.CheckIfBsmReceivedDuringSorting(i.LastVid, BSM.Timestamp.Value) /*bsm rush ricevuto in qualsiasi momento a partire dall'inserimento del bagaglio nel sistema*/)))
                {
                    if(i.BagDestinationStatus != "Late" && !BSMException.Contains("RUSH") && !BSMException.Contains("RRTE"))
                        _trackingDAL.SetItemBagDestinationStatus(i, "Late", TP.TPSorterStart, TP.EdgeStartID, i.idBSM, out errMessage);

                    var rushBagId = _cdgDAL.GetMissedRushBSM(i.BagId);

                    if (rushBagId == null && maxRecirc > RecThresholdRush)
                    {
                        var isAllocated = _sortingDAL.SpecialSortOnBSM(BSM, out errMessage).Any();

                        var spec = SpecialSortsEnum.Late;

                        if (!isAllocated) //if missed bag is in flight not allocated, send to no allocation special target
                            spec = SpecialSortsEnum.NoAllocation;

                        targets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, spec, out errMessage);
                        foreach (var target in targets)
                        {
                            target.SortingReason = "SPECIAL";
                            target.SortingReasonSpecial = spec;
                        }

                        if (!targets.Any())
                        {
                            targets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.NotOk, out errMessage);
                            foreach (var target in targets)
                            {
                                target.SortingReason = "SPECIAL";
                                target.SortingReasonSpecial = SpecialSortsEnum.NotOk;
                            }
                        }

                        _sortingDAL.SaveItemDestinations(targets.ConvertAll(t =>
                         new ItemDestination()
                            {
                                bActive = true,
                                bOverridable = false,
                                DestinationType = t.SortingReason,
                                SortingReasonSpecial = t.SortingReasonSpecial,
                                IDItem = i.ID,
                                IDDestination = t.Target,
                                IDMessage = 0,
                                OrderIndex = 1,
                                SortingReason = t.SortingReason,
                                VID = i.LastVid,
                                Timestamp = DateTime.Now
                            }
                        ), out errMessage);

                        return targets;
                    }

                    //continue recirculation and return
                    if (rushBagId == null)
                        return new List<TargetSortData>();

                    //here send to special exit for missed bags that received the rush bsm while on the sorter so they are not relabeled
                    targets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.ToRelabel, out errMessage);

                    if (!targets.Any())
                        targets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.NotOk, out errMessage);

                    foreach (var target in targets)
                    {
                        target.SortingReason = "SPECIAL";
                        target.SortingReasonSpecial = SpecialSortsEnum.ToRelabel;
                    }

                    _sortingDAL.SaveItemDestinations(targets.ConvertAll(t =>
                         new ItemDestination()
                         {
                             bActive = true,
                             bOverridable = false,
                             DestinationType = t.SortingReason,
                             SortingReasonSpecial = t.SortingReasonSpecial,
                             IDItem = i.ID,
                             IDDestination = t.Target,
                             IDMessage = 0,
                             OrderIndex = 1,
                             SortingReason = t.SortingReason,
                             VID = i.LastVid,
                             Timestamp = DateTime.Now
                         }
                        ), out errMessage);

                    return targets;
                }

                bool bAlternatives = targets.Any() && (targets[0].AlternativeTarget1.HasValue) && (targets[0].AlternativeTarget1.Value > 0);
                
                // If there are alternative chutes, consider first threshold to add them
                if (bAlternatives && maxRecirc > RecThreshold1)
                    foreach (var altTarget in new List<int?> { targets[0].AlternativeTarget1, targets[0].AlternativeTarget2 })
                        if (altTarget.HasValue && !targets.Where(t => t.Target == altTarget).Any())
                            CloneTarget(targets, 0, altTarget.Value);

                // If there are alternative chutes, consider sum of threshold to add REJECT
                // If no alternative chutes, consider only first threshold to add REJECT
                if ((bAlternatives && maxRecirc > RecThreshold1 + RecThreshold2) || (!bAlternatives && maxRecirc > RecThreshold1))
                {
                    List<TargetSortData> special = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, "NOTOK", out errMessage);
                    if (special.Any())
                        targets.AddRange(special);
                }

                return targets;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return targets;
            }
        }

        private bool isBeforePIM(int componentIndex)
        {
            var dpBeforePIM = new List<int>() { 7011, 7021, 7031, 7041, 7051, 7061, 7111, 7121, 7131, 7141, 7151, 7161, 7112, 7122, 7142, 7162, 7211, 7221, 7231, 7241, 7214, 7215, 7216, 7217, 7224, 7225, 7226, 7227 };
            return dpBeforePIM.Contains(componentIndex);
        }

        public override bool ManageSpecialModeFinalTargets(string Caller, Common.DataAccess.GW.Item item, LAP.BaseMessage destReq, Common.DataAccess.GW.ITrackingEvents tracker, ref SortingEdgeData TP,
                                                   ref List<TargetSortData> FinalTargets, ref List<TargetSortData> SelectedTargets,
                                                   ref List<TargetSortData> EBSTargets, ref List<SortingEdgeData> edges, out Common.DataAccess.GW.FlightData Flight, out BSMData BSM,
                                                   out String IATAcode, out string KID, out bool bSentMES, out bool bSentOCR, out int ocrProcQueueId, out string errMessage)
        {
            IATAcode = string.Empty;
            KID = string.Empty;
            bSentOCR = false;
            bSentMES = false;
            ocrProcQueueId = -1;
            errMessage = string.Empty;
            Flight = null;
            BSM = null;
            List<BSMData> BSMs = new List<BSMData>();

            GlobalConf.Refresh();

            int SortingMode = (int)(GlobalConf["SORTING_MODE"] ?? (int)CDGSortingMode.Nominal);

            // 3 = MLP
            // 2 = CRISE RUSH
            // 0 = TSG
            if (SortingMode == (int)CDGSortingMode.TestSecuriteGlobale)
            {
                var trgClean = _sortingDAL.GetSortingTargetByName((string)(GlobalConf["TSG_CLEAN_TARGET"] ?? ""), out errMessage);
                int targetClean = trgClean != null ? trgClean.componentindex : 43;

                var trgSuspect = _sortingDAL.GetSortingTargetByName((string)(GlobalConf["TSG_SUSPECT_TARGET"] ?? ""), out errMessage);
                int targetSuspect = trgSuspect != null ? trgSuspect.componentindex : 44;

                FinalTargets = new List<TargetSortData>() { new TargetSortData() { Target = item.bSecurityStatus ? targetClean : targetSuspect, TargetType = "FINAL", bOverridable = false } };
                return true;
            }
            
            if (SortingMode == (int)CDGSortingMode.MLP || SortingMode == (int)CDGSortingMode.BSMRush || SortingMode == (int)CDGSortingMode.PreTri)
            {
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
                }
                else
                {
                    BSMs = _sortingDAL.FindBSM(BarcodeToFind, out errMessage);
                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"BSMs found: {string.Join(", ", BSMs.Select(e => e.BagId))}");

                    int inboundCount = BSMs.Where(b => b.BaggageType == "I").Count();
                    int outboundCount = BSMs.Count() - inboundCount;

                    tracker.AddTimingStep("FindBSM");

                    if (inboundCount > 0)
                        BSMs = BSMs.Where(b => b.BaggageType != "I").ToList();

                    if (Caller == "SORTING")
                    {
                        try
                        {
                            SetScannerStats(item, TP, destReq.Barcodes.Count, destReq.Barcodes.Count != 0 ? BarcodeToFind.Count() : 0, BSMs.Count(),
                            ((LAP.DestinationRequestMsgExt)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 1) == 1).Any(),
                            ((LAP.DestinationRequestMsgExt)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 2) == 2).Any());
                        }
                        catch (Exception)
                        {
                            tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"Yes barcodes. Could not cast to {nameof(LAP.DestinationRequestMsgExt)}");
                            SetScannerStats(item, TP, destReq.Barcodes.Count, destReq.Barcodes.Count != 0 ? BarcodeToFind.Count() : 0, BSMs.Count(),
                            ((LAP.DestinationRequestMsg)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 1) == 1).Any(),
                            ((LAP.DestinationRequestMsg)destReq).BarcodeData.ArrBarcode.Where(bc => (bc.Value.Type & 2) == 2).Any());
                        }

                        tracker.AddTimingStep("READ - ScannerStats");

                        if (SortingMode == (int)CDGSortingMode.MLP) // MLP
                        {
                            if (BSMs.Where(b => b.TransferLink != "OCR" && b.TransferLink != "MES").Any())
                                _sortingDAL.InvalidateBSMs(BSMs.Where(b => b.TransferLink != "OCR" && b.TransferLink != "MES").ToList(), out errMessage);

                            if (!BSMs.Where(b => b.TransferLink == "OCR" || b.TransferLink == "MES").Any())
                            {
                                // trigger OCR/MES
                                bool bRet = HandleSpecialFlow(TP,
                                                                ref SelectedTargets,
                                                                ref FinalTargets,
                                                                tracker,
                                                                destReq,
                                                                item,
                                                                edges,
                                                                SpecialSortsEnum.NoRead,  /// NO READ ok in this case?
                                                                SpecialSortsEnum.NotOk,
                                                                out ocrProcQueueId,
                                                                out bSentOCR,
                                                                out bSentMES,
                                                                out errMessage);

                                tracker.AddTimingStep("BSM Invalidated - HandleSpecialFlow");

                                if (!string.IsNullOrEmpty(errMessage))
                                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "HandleSpecialFlow", errMessage);  /// TODO... cosa fare??
                                if (!bRet)
                                    return false;   // item queued to OCR
                            }
                        }

                        if (SortingMode == (int)CDGSortingMode.PreTri)
                        {
                            // TODO: query su nuova tab... FLC FLN FLX WeekDay Chute
                            //FinalTargets = chute

                            if (BSMs.Where(b => b.TransferLink != "OCR" && b.TransferLink != "MES").Any())
                                _sortingDAL.InvalidateBSMs(BSMs.Where(b => b.TransferLink != "OCR" && b.TransferLink != "MES").ToList(), out errMessage);

                            if (BSMs.Where(b => b.TransferLink == "OCR" && b.TransferLink == "MES").Any())
                            {
                                BSM = BSMs.Where(b => b.TransferLink == "OCR" || b.TransferLink == "MES").OrderBy(e => e.Timestamp).LastOrDefault();

                                if (BSM == null)
                                {
                                    _trackingDAL.SetItemBagReadStatus(item, BarcodeToFind.Count() > 1 ? "MultipleRead" : "NoBsm", out errMessage);
                                    return false;
                                }

                                _trackingDAL.SetItemBagReadStatus(item, "BagOk", out errMessage);

                                string flightINB = FlightUtils.ParseAndFormatFlight(BSM.Flight, 4, out var FLC, out var FLN, out var FLX, true);
                                //int dayOfWeekMondayBased = ((int)BSM.SDT.DayOfWeek + 6) % 8 + 1;
                                //weekday in reality is used as day span in the past
                                int dayOfWeekMondayBased = (int)(DateTime.Today - BSM.SDT).TotalDays;
                                var preTriTrg = _sortingDAL.GetCDGPreTri(FLC, FLN, FLX, dayOfWeekMondayBased);

                                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Pre Tri Flight: " + BSM.Flight + " " + BSM.SDT);

                                if (preTriTrg != null)
                                {
                                    FinalTargets = new List<TargetSortData>() { new TargetSortData() { Target = preTriTrg.Value, TargetType = "FINAL", bOverridable = false } };
                                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Final targets after sort Pre Tri: " + string.Join(',', FinalTargets.Select(at => at.Target).ToList()));
                                    return true;
                                }

                                FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, "", FLC, SpecialSortsEnum.NoAllocation, out errMessage);
                                foreach (var target in FinalTargets)
                                {
                                    target.SortingReasonSpecial = SpecialSortsEnum.NoAllocation;
                                    target.SortingReason = "SPECIAL";
                                    target.bOverridable = false;
                                }

                                return true;
                            }

                            _trackingDAL.SetItemBagReadStatus(item, BarcodeToFind.Count() > 1 ? "MultipleRead" : "NoBsm", out errMessage);

                            // trigger OCR/MES
                            bool bRet = HandleSpecialFlow(TP,
                                                            ref SelectedTargets,
                                                            ref FinalTargets,
                                                            tracker,
                                                            destReq,
                                                            item,
                                                            edges,
                                                            SpecialSortsEnum.NoRead,  /// NO READ ok in this case?
															SpecialSortsEnum.NotOk,
                                                            out ocrProcQueueId,
                                                            out bSentOCR,
                                                            out bSentMES,
                                                            out errMessage);

                            tracker.AddTimingStep("BSM Invalidated - HandleSpecialFlow");

                            if (!string.IsNullOrEmpty(errMessage))
                                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "HandleSpecialFlow", errMessage);  /// TODO... cosa fare??
							if (!bRet)
                                return false;   // item queued to OCR
                            
                        }
                    }
                }


                if (SortingMode == (int)CDGSortingMode.MLP)
                {
                    BSMs = BSMs.Where(b => b.TransferLink == "OCR" || b.TransferLink == "MES").ToList();

                    if (BSMs.Any())
                    {
                        BSM = BSMs[0];

                        _trackingDAL.SetItemBagStatus(item, BSM.ID, BSM.BagId, BSM.BaggageType, BSM.SDT, BSM.Flight, BSM.DES, BSM.FLC, BSM.FLN, string.Empty, "BagOk", false, TP.TPSorterStart, TP.EdgeStartID, out errMessage);

                        FinalTargets = _sortingDAL.SortByDES(BSMs[0].FLC, BSMs[0].DES, out errMessage);

                        tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Final targets after sort MLP: " + string.Join(',', FinalTargets.Select(at => at.Target).ToList()));

                        if (!FinalTargets.Any())
                        {
                            FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.NoAllocation, out errMessage);
                            foreach (var target in FinalTargets)
                            {
                                target.SortingReason = "SPECIAL";
                                target.SortingReasonSpecial = "NOALLOCATION";
                                target.bOverridable = true;
                            }
                            tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Send to special sort NOALLOCATION overridable");
                        }
                    }
                }

                if (SortingMode == (int)CDGSortingMode.BSMRush && BSMs.Any() && item.BagDestinationStatus != "Late" && !isBeforePIM(TP.ComponentIndexStart))
                {
                    BSM = BSMs.OrderBy(b => b.Timestamp).Last();

                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"Found in {nameof(ManageSpecialModeFinalTargets)} BagId: {BSM.BagId}, FLC: {BSM.FLC}, FLN: {BSM.FLN}, SDT: {BSM.SDT}");

                    FinalTargets = _sortingDAL.SpecialSortOnBSM(BSM, out errMessage);

                    tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Final targets after Sort_GetCriseRush: " + string.Join(',', FinalTargets.Select(at => at.Target).ToList()));

                    if (!FinalTargets.Any())
                    {
                        FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.NoAllocation, out errMessage);
                        foreach (var target in FinalTargets)
                        {
                            target.SortingReason = "SPECIAL";
                            target.SortingReasonSpecial = "NOALLOCATION";
                            target.bOverridable = true;
                        }
                        tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", "Send to special sort NOALLOCATION overridable");
                    }
                }
            }
            
            if (SortingMode == (int)CDGSortingMode.DeposeSortie)
            {
                FinalTargets = _sortingDAL.SortByDepose(item.ID, out errMessage);
                string msg = "Final targets after Sort_GetDeposeSortie: " + string.Join(',', FinalTargets.Select(at => at.Target).ToList());
                if (!FinalTargets.Any())
                {
                    FinalTargets = _sortingDAL.GetSpecialSort(TP.TPSorterStart, null, null, SpecialSortsEnum.NotOk, out errMessage);
                    foreach (var target in FinalTargets)
                    {
                        target.SortingReason = "SPECIAL";
                        target.SortingReasonSpecial = "NOTOK";
                        target.bOverridable = false;
                    }
                    msg = "Send to special sort NOTOK not overridable";
                }
                tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", msg);
            }

            return true;
        }

        public override bool ManageSpecialLATECase(Item item, ref SortingEdgeData TP, BSMData BSM, ref List<TargetSortData> SelectedTargets, Common.DataAccess.GW.ITrackingEvents tracker, out bool bSentMES, out bool bSentOCR, out string errMessage)
        {
            errMessage = string.Empty;
            bSentMES = false;
            bSentOCR = false;
            string BRException;

            BSMData BRElement = _sortingDAL.GetBSMFromRElement(BSM.BagId, BSM.Flight, out errMessage);
            if (!string.IsNullOrEmpty(errMessage))
                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "GetBSMFromRElement", errMessage);

            GlobalConf.Refresh();

            int SortingMode = (int)(GlobalConf["SORTING_MODE"] ?? (int)CDGSortingMode.Nominal);
            BRException = (BRElement != null && BRElement.Exception != null) ? BRElement.Exception : "-";
            tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"call ManageSpecialLATECase [{BSM.BagId}] [{BSM.Flight}] [{SortingMode}] [{BRException}] [{BSM.Exception}] ");
            
            // Case BIG RUSH or BIG RRTE
            if ((SortingMode == (int)CDGSortingMode.Nominal || SortingMode == (int)CDGSortingMode.BSMRush) &&
                ((item.bCodedByMES ?? false) == false) &&
                BRElement != null &&
                BRElement.BagId != BSM.BagId &&
                (BRException.ToUpper().Contains("RUSH") && CheckMESReasonEnabled(MesBaggagePresenceReason.ReLabelBig.ToString(), BSM.FLC))
                || BRException.ToUpper().Contains("RRTE") && CheckMESReasonEnabled(MesBaggagePresenceReason.ReRouted.ToString(), BSM.FLC))
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    _trackingDAL.SetItemBagReadStatus(item,
                        BRException.ToUpper().Contains("RUSH") ?
                            MesBaggagePresenceReason.ReLabelBig.ToString() :
                            MesBaggagePresenceReason.ReRouted.ToString(),
                        out errMessage);

                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagReadStatus", errMessage);

                    _trackingDAL.SetItemControlID(item.ID, BRElement.BagId, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemControlID", errMessage);

                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            return true;
        }

        public override bool ManageBSMSpecialCase(Common.DataAccess.GW.Item item, ref SortingEdgeData TP, BSMData BSM, ref List<TargetSortData> SelectedTargets, Common.DataAccess.GW.ITrackingEvents tracker, out bool bSentMES, out bool bSentOCR, out string errMessage)
        {
            errMessage = string.Empty;
            bSentMES = false;
            bSentOCR = false;

            GlobalConf.Refresh();
            tracker.WriteMessage(TrackingLevel.Debug, "GENERIC", $"call ManageBSMSpecialCase");

            int SortingMode = (int)(GlobalConf["SORTING_MODE"] ?? (int)CDGSortingMode.Nominal);

            // Case BSM DEL
            if (((SortingMode == (int)CDGSortingMode.Nominal) ||
                 (SortingMode == (int)CDGSortingMode.BSMRush) ||
                 (SortingMode == (int)CDGSortingMode.PreTri)) &&
                (BSM.BSMtext.StartsWith("BSM\r\nDEL")
                || BSM.BSMtext.StartsWith("BSM DEL")))
            {
                SelectedTargets = ManageSpecialSort(TP, tracker, null, null, SpecialSortsEnum.BSMDEL, SpecialSortsEnum.NotOk, out errMessage);
                return true;
            }

            BSMData BRElement = _sortingDAL.GetBSMFromRElement(BSM.BagId, BSM.Flight, out errMessage);
            if (!string.IsNullOrEmpty(errMessage))
                tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "GetBSMFromRElement", errMessage);

            // Case MINI RUSH
            if ((SortingMode == (int)CDGSortingMode.Nominal || SortingMode == (int)CDGSortingMode.BSMRush) &&
                ((item.bCodedByMES ?? false) == false) &&
                BSM.Exception != null &&
                BSM.Exception.ToUpper().Contains("RUSH") &&
                !BSM.Exception.ToUpper().Contains("RRTE") &&
                !BSM.Exception.ToUpper().Contains("RLBD") &&
                CheckMESReasonEnabled(MesBaggagePresenceReason.ReLabelMini.ToString(), BSM.FLC) &&
                !_cdgDAL.CheckIfBagRelabeled(BSM.BagId, false) &&
                (BRElement == null || BRElement.BagId == BSM.BagId)
                )
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    _trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.ReLabelMini.ToString(), out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagReadStatus", errMessage);

                    _trackingDAL.SetBagId(item, BSM.ID, BSM.BagId, BSM.SDT, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetBagId", errMessage);

                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            // Case BIG RUSH - Extraction
            if ((SortingMode == (int)CDGSortingMode.Nominal || SortingMode == (int)CDGSortingMode.BSMRush) &&
                (BRElement != null) &&
                (BRElement.FLC == "1AF") &&
                (BRElement.BagId != BSM.BagId) &&
                ((item.bCodedByMES ?? false) == false) &&
                BRElement.Exception != null &&
                BRElement.Exception.ToUpper().Contains("RUSH") &&
                CheckMESReasonEnabled(MesBaggagePresenceReason.ReLabelBig.ToString(), BRElement.FLC) &&
                !_cdgDAL.CheckIfBagRelabeled(BRElement.BagId, false)
                )
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    _trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.ReLabelBig.ToString(), out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagReadStatus", errMessage);

                    _trackingDAL.SetItemControlID(item.ID, BRElement.BagId, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemControlID", errMessage);

                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            if (SortingMode == (int)CDGSortingMode.BSMRush && BSM != null && (BSM.Exception == null || (!BSM.Exception.Contains("RUSH") && !BSM.Exception.Contains("RRTE"))) && _cdgDAL.CheckIfFlightDepartureGone(item.Flight, item.SDT))
            {
                _trackingDAL.SetItemBagDestinationStatus(item, "Late", TP.TPSorterStart, TP.EdgeStartID, BSM.ID, out errMessage);
                item.BagDestinationStatus = "Late";
                if (!string.IsNullOrEmpty(errMessage))
                    tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagDestinationStatus", errMessage);
            }

            // Case Raté
            if (SortingMode == (int)CDGSortingMode.BSMRush &&
                ((item.bCodedByMES ?? false) == false) &&
                (BSM.Exception == null ||
                (!BSM.Exception.ToUpper().Contains("RUSH") &&
                !BSM.Exception.ToUpper().Contains("RRTE") &&
                !BSM.Exception.ToUpper().Contains("RLBD"))) &&
                item.BagDestinationStatus == "Late" &&
                CheckMESReasonEnabled(MesBaggagePresenceReason.Missed.ToString(), BSM.FLC) &&
                !_cdgDAL.CheckIfBagRelabeled(BSM.BagId, false)
                )
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    _trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.Missed.ToString(), out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagReadStatus", errMessage);

                    _trackingDAL.SetBagId(item, BSM.ID, BSM.BagId, BSM.SDT, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetBagId", errMessage);

                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            // Case (Mini) ReRouted
            if ((SortingMode == (int)CDGSortingMode.Nominal || SortingMode == (int)CDGSortingMode.BSMRush) &&
                ((item.bCodedByMES ?? false) == false) &&
                BSM.Exception != null &&
                BSM.Exception.ToUpper().Contains("RRTE") &&
                !BSM.Exception.ToUpper().Contains("RLBD") &&
                CheckMESReasonEnabled(MesBaggagePresenceReason.ReRouted.ToString(), BSM.FLC) &&
                !_cdgDAL.CheckIfBagRelabeled(BSM.BagId, false) &&
                (BRElement == null || BRElement.BagId == BSM.BagId)
                )
            {
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, AreaSortsEnum.MES, out errMessage);
                if (SelectedTargets.Any())
                {
                    _trackingDAL.SetItemBagReadStatus(item, MesBaggagePresenceReason.ReRouted.ToString(), out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetItemBagReadStatus", errMessage);

                    _trackingDAL.SetBagId(item, BSM.ID, BSM.BagId, BSM.SDT, out errMessage);
                    if (!string.IsNullOrEmpty(errMessage))
                        tracker.WriteMessage(TrackingLevel.Error, "EXCEPTION", "SetBagId", errMessage);

                    bSentMES = true;
                    tracker.WriteMessage(TrackingLevel.Info, "TARGET_MES", string.Join(',', SelectedTargets.Select(at => at.Target).ToList()));
                }
            }

            if(!SelectedTargets.Any() && SortingMode == (int)CDGSortingMode.BSMRush)
                ManageSpecialLATECase(item, ref TP, BSM, ref SelectedTargets, tracker, out bSentMES, out bSentOCR, out errMessage);

            //bagaglio normale o comunque big rush rietichettato
            if (!SelectedTargets.Any() && SortingMode == (int)CDGSortingMode.BSMRush && isBeforePIM(TP.ComponentIndexStart))
                SelectedTargets = _sortingDAL.GetAreaSort(item, TP, "XRay", out errMessage);

            return true;
        }
    }
}
