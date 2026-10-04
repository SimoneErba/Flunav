using Microsoft.Extensions.Configuration;
using System;
using System.Collections.Generic;
using EasyAirport.Common.Utilities;
using System.Data.SqlClient;
using System.Linq;
using Dapper;
using Dapper.Contrib.Extensions;
using System.Data;
using Newtonsoft.Json;
using EasyAirport.Common.Messages;
using EasyAirport.Common.DataAccess.GW;
using OCR.Models;
using Pipelines.Sockets.Unofficial.Arenas;
using NLog.Targets;
using System.Net.NetworkInformation;
using System.Runtime.Serialization;
using System.Security.Cryptography;
using System.Linq.Expressions;


namespace EasyAirport.Common.DataAccess.DB
{
    public class SortingDB : ISortingDAL
    {
        protected IConfigGlobal GlobalConf = null;
        protected string connectionstring;
        string SACBaseConnectionString = null;
        IClock _clock = null;
        private static Dictionary<string, string> ScreeningResultDescr = null;
        bool useCache = true;
        static CacheData<GW.SpecialSortTypes> SpecialSortTypes = null;
        static CacheData<GW.SecurityStatusEnumDescription> SecurityStatusEnumDescriptions = null;
        static CacheData<GW.Component> Components = null;
        static CacheData<SortingEdgeData> SortingEdges = null;
        static CacheData<HBSTrackingLostPLCList> HBSLostPLC = null;
        static CacheData<HBSTrackingLostTPList> HBSLostTP = null;
        public SortingDB(IConfiguration iconf, IConfigGlobal _GlobalConf, IClock clock)
        {
            GlobalConf = _GlobalConf;

            _clock = clock;
            connectionstring = iconf["Data:SACDBConnection:ConnectionString"];
            SACBaseConnectionString = iconf["Data:SACBaseConnection:ConnectionString"];

            if (useCache)
            {
                InitCache();
            }

        }

        public SortingDB(string connStringSACDB, string connStringSACBase, IConfigGlobal _GlobalConf, IClock clock)
        {
            GlobalConf = _GlobalConf;

            _clock = clock;
            connectionstring = connStringSACDB;
            SACBaseConnectionString = connStringSACBase;

            if (useCache)
            {
                InitCache();
            }

        }

        private void InitCache()
        {
            if (SpecialSortTypes == null)
                SpecialSortTypes = new CacheData<GW.SpecialSortTypes>(connectionstring, "select * from SpecialSortTypes");
            if (SecurityStatusEnumDescriptions == null)
                SecurityStatusEnumDescriptions = new CacheData<GW.SecurityStatusEnumDescription>(connectionstring, "select * from SecurityStatusEnumDescription");
            if (Components == null)
                Components = new CacheData<GW.Component>(connectionstring, @"select c.*,ct.name ComponentType, cst.name ComponentSubType ,pc.name ParentComponent,ppc.name GrandparentComponent,c.MasterComponentIndex
                                                                                from Component c
                                                                                join ComponentType ct on ct.id=c.IDComponentType
                                                                                left join ComponentSubType cst on cst.id=c.componentsubindex
                                                                                LEFT JOIN component pc ON pc.id=c.IDParentComponent
                                                                                LEFT JOIN component ppc ON ppc.id=pc.IDParentComponent");
            if (SortingEdges == null)
                SortingEdges = new CacheData<SortingEdgeData>(connectionstring, "select * from vwSortingEdges");

            if (HBSLostPLC == null)
                HBSLostPLC = new CacheData<HBSTrackingLostPLCList>(connectionstring, "select * from HBSTrackingLostPLCList");

            if (HBSLostTP == null)
                HBSLostTP = new CacheData<HBSTrackingLostTPList>(connectionstring, "select * from HBSTrackingLostTPList");
        }


        public bool FlightIsClosed(DateTime SDT, string Flight, out string errMessage)
        {
            errMessage = String.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    string FlightStatus = connection.ExecuteScalar<string>(@"select dsf.OpenStatus
                                                                            from vwDailySortingFlights dsf
                                                                            join DailySorting ds on ds.id = dsf.iddailysorting
                                                                            where ds.SortingDate = @sdt and dsf.flight = @flight", new { sdt = SDT, flight = Flight });

                    if (string.IsNullOrEmpty(FlightStatus))
                        return false;
                    else if (FlightStatus.ToUpper() == "CLOSED")
                        return true;
                    else
                        return false;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public string GetSpecialSortDescr(string special)
        {
            try
            {
                if (useCache)
                {
                    SpecialSortTypes spt = SpecialSortTypes.Value.Where(sp => sp.Name == special).FirstOrDefault();
                    if (spt == null)
                        return special;
                    else
                        return spt.Description ?? spt.Name;
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        return connection.ExecuteScalar<string>("SELECT ISNULL(description,name) FROM dbo.SpecialSortTypes WHERE name=@spec", new { spec = special });
                    }
                }
            }
            catch (Exception ex)
            {
                return string.Empty;
            }

        }

        public List<SortingDestinationConfig> GetSortingDestinationConfigs(out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<SortingDestinationConfig>("select * from vwSortingDestinationsGW").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<SortingDestinationConfig>();
            }

        }

        public vwSortingTargets GetSortingTargetByName(string TargetName, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<vwSortingTargets>("select top(1) * from vwSortingTargets where target=@TargetName", new { TargetName }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }

        public List<vwSortingTargets> GetSortingTargetByArea(string areaName, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<vwSortingTargets>("select * from vwSortingTargets where aggregatetype='Sorter' and sortingsystem=@AreaName", new { AreaName =areaName}).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<vwSortingTargets>();
            }
        }


        public virtual BSMData GetBSM(string BagId, string FLC, int FLN, DateTime SDT, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<BSMData>("select * from bsms where bagid = @bagid and flc = @flc and fln = @fln and sdt = @dt and bValid=1", new { bagid = BagId, flc = FLC, fln = FLN, dt = SDT }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }

        public BSMData GetBSM(int id, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<BSMData>("select * from BSMS where id=@id", new { id = id }).FirstOrDefault();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public int? GetCDGPreTri(string FLC, int FLN, string FLX, int dayOfWeekMondayBased, out string errMessage)
        {
            errMessage = string.Empty;
            if (string.IsNullOrEmpty(FLX))
                FLX = null;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<int>("select Trg from CDGPreTri where FLC=@FLC and FLN=@FLN and " + FLX != null ? "FLX = @FLX" : "FLX is null" + " and Weekday=@dayOfWeekMondayBased", new { FLC, FLN, FLX, dayOfWeekMondayBased }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public bool InsertScannerStatistics(GW.ScannerStatistics stat, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Insert<GW.ScannerStatistics>(stat);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool InvalidateBSMs(List<BSMData> BSMs, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    DataTable tvp = new DataTable();
                    tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                    // populate DataTable from your List here
                    foreach (var id in BSMs.Select(ft => ft.ID).Distinct())
                        tvp.Rows.Add(id);

                    connection.Execute("Sort_InvalidateBSM", new { BagId = tvp.AsTableValuedParameter("IntList") }, commandType: CommandType.StoredProcedure);
                }

                return true;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }




        }




        public List<BSMData> FindBSM(List<string> bagIds, out string errMessage)
        {
            List<BSMData> retList = new List<BSMData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    foreach (string _bagid in bagIds)
                        retList.AddRange(connection.Query<BSMData>("Sort_GetBSM", new { BagId = _bagid },
                                                                        commandType: CommandType.StoredProcedure).ToList());

                }

                //List<string> bag3 = retList.Where(b => b.BagId.StartsWith('3')).Select(b => b.bagid).Distinct().ToList() ;

                if (retList.Where(b => b.BagId.StartsWith('3')).Any())
                {
                    retList = retList.Where(b => b.BagId.StartsWith('3')).GroupBy(b => b.BagId).Select(b => b.FirstOrDefault()).ToList();
                    //retList = new List<BSMData>() { retList.Where(b => b.BagId.StartsWith('3')).FirstOrDefault() };
                }
                return retList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }

        public bool UpdateMESStatus(string StationName, bool bStatus, bool PLCInhibit, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("update MESConfiguration set bCurrentStatus=@sts,PLCInhibit=@plcinhibit where FunctionName=@func",
                        new { sts = bStatus, plcinhibit = PLCInhibit, Func = StationName });
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public string GetMESType(string StationName, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<string>("select type from MESConfiguration where FunctionName=@fname", new { fname = StationName }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return "Normal";
            }
        }

        public List<GW.FlightData> FindFlight(DateTime SDT, string FLC, int FLN, string FLX, out string errMessage)
        {
            List<GW.FlightData> retList = new List<GW.FlightData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    retList.AddRange(connection.Query<GW.FlightData>("Sort_FindFlight", new { flc = FLC, fln = FLN, flx = FLX, sdt = SDT },
                                                                commandType: CommandType.StoredProcedure).ToList());

                }
                return retList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }

        public List<GW.FlightData> FindFlightWithType(DateTime SDT, string FLC, int FLN, string FLX, string flightType, out string errMessage)
        {
            List<GW.FlightData> retList = new List<GW.FlightData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    retList.AddRange(connection.Query<GW.FlightData>("Sort_FindFlight", new { flc = FLC, fln = FLN, flx = FLX, sdt = SDT, FlightType=flightType },
                                                                commandType: CommandType.StoredProcedure).ToList());

                }
                return retList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }


        public long SaveBPM(BPMS bpm, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Insert<BPMS>(bpm);

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return -1;
            }
        }
        public long SaveBPMQueue(BPMQueue bq, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Insert<BPMQueue>(bq);

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return -1;
            }
        }




        public FlightData GetFlight(int idFlight, out string errMessage)
        {
            List<FlightData> retList = new List<FlightData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<FlightData>("select * from dailyflights where id=@id", new { id = idFlight }).FirstOrDefault();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public configAirlines GetAirline(string FLC, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<configAirlines>("select * from configAirlines where FLC=@airline", new { airline = FLC }).FirstOrDefault();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }
        public configAirports GetAirport(string AirportIATA, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<configAirports>("select * from configAirports where IATAcode=@iata", new { iata = AirportIATA }).FirstOrDefault();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public string ConvertScreeningResultToDescription(string Result)
        {
            string resString = null;
            if (Result == null)
                return resString;
            if (useCache)
            {
                SecurityStatusEnumDescription secStatusDescr = SecurityStatusEnumDescriptions.Value.Where(ssd => ssd.SecStatus == Result).FirstOrDefault();
                if (secStatusDescr != null)
                    return secStatusDescr.Descr;
                else
                    return Result;

            }
            else
            {
                if (ScreeningResultDescr == null)
                {

                    try
                    {
                        using (var connection = new SqlConnection(connectionstring))
                        {
                            ScreeningResultDescr = connection.Query<SecurityStatusEnumDescription>(@"SELECT * FROM securitystatusenumdescription").ToDictionary(t => t.SecStatus, t => t.Descr);
                        }
                    }
                    catch (Exception ex)
                    {
                    }
                }

                if (ScreeningResultDescr == null)
                    return resString;

                if (!ScreeningResultDescr.TryGetValue(Result, out resString))
                    resString = Result;

                return resString;
            }

        }

        public SecurityStatusEnumDescription GetSecurityStatusEnumDescription(string Result, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                if (useCache)
                {
                    return SecurityStatusEnumDescriptions.Value.Where(ssd => ssd.SecStatus == Result).FirstOrDefault();
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        return connection.Query<SecurityStatusEnumDescription>(@"SELECT * FROM securitystatusenumdescription where SecStatus=@res", new { res = Result }).FirstOrDefault();
                    }
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }


        public List<ItemSecurity> GetPendingSecurityBPM(long idItem, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ItemSecurity>(@"SELECT * FROM ItemSecurity where iditem=@id and bpmPending=1", new { id = idItem }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ItemSecurity>();
            }
        }

        public bool UpdateItemSecurity(ItemSecurity iSec, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Update<ItemSecurity>(iSec);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public List<HBSSubmit> GetLastHBSSumbitTimestamp(long itemID, out string errMessage)
        {

            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<HBSSubmit>(@"select e.tpidend TPid,c.ComponentIndex,max(ir.timestamp) Timestamp
                                                        from itemresult ir
                                                        join SortingEdgeEnd e on ir.IDTrackingPoint=e.TPIDEnd and e.HBSid is not null
                                                        join component c on c.TrackingPointID=e.HBSid
                                                        where ir.iditem=@iditem and ir.idresult=1 and ir.idresulttype=1
                                                        group by e.tpidend,c.ComponentIndex
                                                        order by 3 desc
                                                        ", new { iditem = itemID }).ToList();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<HBSSubmit>();
            }

        }

        public virtual List<TargetSortData> SortByDES(string FLC, string DES, out string errMessage)
        {
            errMessage= string.Empty;
            return new List<TargetSortData>();
        }

        public virtual List<TargetSortData> SpecialSortOnBSM(BSMData BSM, out string errMessage)
        {
            errMessage = string.Empty;
            return new List<TargetSortData>();
        }



        public virtual List<FinalTargetsData> GetFinalTargets(int IdFlight, string DES, string FlightClass, string OnwardFlight, string OnwardDES, string EElement, string BSMText, out string errMessage)
        {
            List<FinalTargetsData> retList = new List<FinalTargetsData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    retList.AddRange(connection.Query<FinalTargetsData>("Sort_GetFinalTarget", new { IDFlight = IdFlight, des = DES, FlightClass = FlightClass, OnwardFlight = OnwardFlight, OnwardDes = OnwardDES, EElement = EElement },
                                     commandType: CommandType.StoredProcedure).ToList());


                    if (retList.Count > 0)
                    {
                        int firstPrio = retList[0].Prio;

                        return retList.Where(r => r.Prio == firstPrio).ToList();
                    }
                }
                return retList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }

        public List<TargetSortData> GetFinalTargetByAirlineCode(int airlineNumCode, out string errMessage)
        {
            errMessage = string.Empty;
            List<TargetSortData> retList = new List<TargetSortData>();

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    int tgt = connection.ExecuteScalar<int>(@"DECLARE @tg int

                                                            SELECT @tg=ca.airlinesortingtarget
                                                            FROM dbo.ConfigAirlines ca
                                                            WHERE ca.airlinecode=@aircode

                                                            SELECT ISNULL(@tg,CAST(cg.value AS INT))
                                                            FROM dbo.ConfigGlobal cg 
                                                            WHERE cg.NAME='AIRLINE_SORTING_DEFAULT'
                                                            ", new { aircode = airlineNumCode });

                    retList.Add(new TargetSortData() { bOverridable=false, Target=tgt, TargetType="FINAL" });

                }
                return retList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }

        


        public OCRProcessingInfo GetOCRProcessingCommand(int OCRprocInfo, int GWid, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<OCRProcessingInfo>(@"select ID,VID,AtrID,bVcd,bagidlist,FLCHint,FLNHint,DESHint  from ocrprocessinginfo where id=@id",new { id = OCRprocInfo }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }


        public List<ItemDestination> ConvertToItemDestinations(Item item, MesCodeBaggageMessage MesCodeBag, out string errMessage)
        {
            List<ItemDestination> itemDestinations = new List<ItemDestination>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

//                    var results = connection.Query("select AggregateName, ComponentIndex from vwSortingTargets where aggregatetype = 'Sorter' and [target] in @list", new { list = MesCodeBag.DestinationTargets });

                    var results = connection.Query("SELECT Sorter AggregateName,ComponentIndex FROM vwsortingtargetsaggregated WHERE aggregatetype in ('Sorter','SPG') AND target IN  @list", new { list = MesCodeBag.DestinationTargets });
                    //**cache
                    foreach (var res in results)
                    {
                        ItemDestination id = new ItemDestination();
                        id.IDItem = item.ID;
                        id.IDDestination = res.ComponentIndex;
                        id.Sorter = res.AggregateName;
                        id.bOverridable =  MesCodeBag.Overridable;
                        id.bActive = true;
                        id.OrderIndex = 1;
                        id.Timestamp = _clock.Now;
                        id.DestinationType = "FINAL";

                        itemDestinations.Add(id);
                    }

                    return itemDestinations;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ItemDestination>();
            }
        }

                    
        public bool SetValidBSM(string BagId, int idBSM, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE bsms SET bValid = 0 WHERE bagid = @bagid AND id != @BSMId ", new { bagid=BagId, BSMId = idBSM});

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }



        public bool CreatePseudoBSM(string BagId, DateTime SDT, string Flight, string DES, string Source, string onwFlight, string onwDES, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    int ndigits = (int)GlobalConf["FLIGHT_NUMBER_DIGITS"];
                    string FLC;
                    string FLX;
                    int FLN;
                    string newflight = FlightUtils.ParseAndFormatFlight(Flight, ndigits, out FLC, out FLN, out FLX, true);
                    connection.Execute("CreatePseudoBSM", new { BagId = BagId, SDT = SDT, flight = newflight, FLC = FLC, FLN = FLN, FLX = FLX, DES = DES, Source = Source, OnwFlight = onwFlight, onwDES = onwDES },
                                        commandType: CommandType.StoredProcedure);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }


        public bool ParseOCRResponse(Response ocrData, out Item item, out LAP.DestinationRequestMsg destreq, out OCRProcessingInfo procInfo, out string errMessage)
        {
            errMessage = string.Empty;
            item = null;
            destreq = null;
            OCRProcessingInfo procinfo = null;
            long VID;
            int ATRid;
            procInfo = null;
            try
            {
                //string[] parts = response.Split(',');
                //if (parts.Length < 10)
                //    return false;

                //dynamic data = JObject.Parse(response);
                //VID = long.Parse(parts[0]);
                //ATRid = int.Parse(parts[1]);
                VID = long.Parse(ocrData.Vid);
                ATRid = ocrData.AtrId;
                //2020092000000011252,41201,0724000001,LX,8,GOA,,OK,OCR,0.1
                using (var connection = new SqlConnection(connectionstring))
                {
                    procinfo = connection.Query<OCRProcessingInfo>("select * from ocrprocessinginfo where vid=@VID and ATRid=@ATR", new { VID = VID, ATR = ATRid }).FirstOrDefault();

                    if (procinfo == null)
                        return false;

                    int? ATRTPid = connection.ExecuteScalar<int>("select distinct idATRTP from vwSortingEdges where idAtrComponent=@idcomp", new { idcomp = ATRid });

                    item = JsonConvert.DeserializeObject<Item>(procinfo.Item);
                    destreq = JsonConvert.DeserializeObject<LAP.DestinationRequestMsg>(procinfo.DestReq);
                    //procinfo.Result = parts[7].ToUpper() == "OK" ? true : false;
                    procinfo.Result = ((string)ocrData.Result).ToUpper() == "OK" ? true : false;
                    procinfo.QueueInsertionTime = ocrData.QueueInsertionTime;
                    procinfo.StartTask = ocrData.StartTask == DateTime.MinValue ? (DateTime?)null : ocrData.StartTask;
                    procinfo.EndTask = ocrData.EndTask == DateTime.MinValue ? (DateTime?)null : ocrData.EndTask;
                    procinfo.TimestampResponse = _clock.Now;
                    procinfo.Encoder = ocrData.Encoder;
                    procinfo.BagIdList = ocrData.BagId;


                    if (procinfo.Result ?? false)
                    {
                        ScannerStatistics scStat = new ScannerStatistics();
                        scStat.TPid = ATRTPid.Value;
                        scStat.IDItem = item?.ID;
                        scStat.OCRRead = true;
                        InsertScannerStatistics(scStat, out errMessage);
                    }

                    if (procinfo.Result??false)
                    {
                        procinfo.FLC = ocrData.Flc; // parts[3];
                        procinfo.FLN = int.Parse(ocrData.Fln);
                        procinfo.FLX = ocrData.Flx;
                        procinfo.DES = ocrData.Des;
                        procinfo.SDT = ocrData.Sdt;
                        procinfo.Score = ocrData.Confidence;
                    } 
                    connection.Update<OCRProcessingInfo>(procinfo);
                    procInfo = procinfo;

                    return procinfo.Result??false;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public string GenerateManualBagId(string firstDigit, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return (string)connection.ExecuteScalar("GenerateManualBagID", new { FirstDigit = firstDigit }, commandType: CommandType.StoredProcedure);
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return string.Empty;
            }
        }

        public string GetMesName(int MESIndex, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                if(useCache)
                {
                    return Components.Value.Where(cc => cc.ComponentType == "MES" && cc.ComponentIndex == MESIndex).Select(cc => cc.Name).FirstOrDefault();
                }
                else
                {

                    using (var connection = new SqlConnection(connectionstring))
                    {
                        return connection.ExecuteScalar<string>(@"select c.name 
                                                                from component c
                                                                join componenttype ct on c.IDComponentType = ct.ID
                                                                where ct.name = 'MES' and c.componentindex = @ID", new { ID = MESIndex });
                    }
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return string.Empty;
            }
        }


        public List<TargetSortData> GetClosestOCR(int TPid, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<TargetSortData>("GetClosestAreaTargets", new { TPid = TPid, AreaSortsEnum.OCR },
                                    commandType: CommandType.StoredProcedure).ToList();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }

        public bool? RefreshOCRStatus(int idOCR, int timeout, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    OCRProcessingInfo ocr = connection.Query<OCRProcessingInfo>("select * from OCRProcessingInfo where id=@id", new { id= idOCR}).FirstOrDefault();

                    if (ocr == null)
                    {
                        errMessage = "MissingRequest";
                        return null;
                    }
                    else
                    {
                        if (ocr.Result.HasValue)
                        {
                            if (ocr.Result.Value)
                            {
                                TimeSpan ts2 = DateTime.Now - ocr.TimestampResponse.Value;
                                if (ts2.TotalSeconds > 2)
                                    ocr.Result = false;
                            }

                            return ocr.Result;
                        }

                        TimeSpan ts = DateTime.Now - ocr.TimestampRequest.Value;
                        if (ts.TotalSeconds > timeout)
                        {
                            ocr.Result = false;
                            ocr.TimestampResponse = _clock.Now;
                            connection.Update(ocr);
                        }


                    }
                    return ocr.Result;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public BSMData GetDeletedBSMFlight(string barcode, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<BSMData>(@"select * 
                                                        from bsms 
                                                        where sdt>=DATEADD(DAY,-1,CAST(GETDATE() AS DATE))
                                                        and bagid=@bid", new { bid = barcode}).FirstOrDefault();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }


        public int ManageOCR(Item item, SortingEdgeData TP, List<string> barcodes, LAP.BaseMessage destReq, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                bool sendNoReadToOcr = (bool)(GlobalConf["SEND_NOREAD_TO_OCR"] ?? false);               
                if (!((sendNoReadToOcr && barcodes.Count == 0) || barcodes.Count == 1))
                    return 0;

                // looks for a BSM DEL with the same BagId, in case it's found the flight will become a hint to OCR

                BSMData bsmDel = null;
                if (barcodes.Count > 0)
                    bsmDel = GetDeletedBSMFlight(barcodes[0], out errMessage); // OCRHint

                using (var connection = new SqlConnection(connectionstring))
                {
                    string sItem = JsonConvert.SerializeObject(item);
                    string sDestReq = JsonConvert.SerializeObject(destReq);
                    // check if already sent to OCR for that VID and ATR
                    return (int)connection.ExecuteScalar("Sort_SendToOCR", new
                    {
                        VID = item.LastVid,
                        TPid = TP.EdgeStartID,
                        ATRid = TP.idATRComponent,
                        bagids = string.Join(',', barcodes),
                        item = sItem,
                        destReq = sDestReq,
                        FLCHint = bsmDel?.FLC,
                        FLNHint = bsmDel?.FLN,
                        DESHint = bsmDel?.DES
                        //                        flightHint= flightHint  // OCRHint
                    }, commandType: CommandType.StoredProcedure);

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return 0;
            }
        }

        public bool SetItemBulky(Item item, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"update item set bulky=1 where id=@iditem", new { iditem = item.ID });
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<TargetSortData> GetTargetSortsFromTargetList(int idPLC, List<int> iRet, out string errMessage)
        {

            errMessage = string.Empty;
            try
            {
                if(useCache)
                {
                    return Components.Value.Where(c => c.IDPLCNode == idPLC && iRet.Contains(c.ComponentIndex) && (c.ComponentType == "Trg" || c.ComponentType == "Outj"))
                        .Select(c => new TargetSortData() { Sorter = c.ParentComponent, Target = c.ComponentIndex, TargetTPid = c.TrackingPointID ?? 0, TargetType = "FINAL" }).ToList();
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        return connection.Query<TargetSortData>(@"select distinct t.Sorter Sorter, t.componentindex [Target],c.TrackingPointID TargetTPid,'FINAL' TargetType
                                                        from dbo.vwSortingTargetsAggregated t
                                                        join component c on t.componentindex = c.ComponentIndex
                                                        where t.AggregateType in ('EBS', 'Sorter') 
                                                        and t.componentindex in @ids AND c.IDPLCNode=@idplc", new { ids = iRet, idplc = idPLC }).ToList();
                    }

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }

        }

        public List<SortingEdgeData> GetSortingEdges(int TPStart,  out string errMessage)
        {
            List<SortingEdgeData> retList = new List<SortingEdgeData>();
            errMessage = string.Empty;
            try
            {
                if(useCache)
                {
                    return SortingEdges.Value.Where(e => e.EdgeStartID == TPStart).ToList();
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        retList.AddRange(connection.Query<SortingEdgeData>(@"select *
                                        from vwSortingEdges 
                                        where EdgeStartId = @StartId", new { StartId = TPStart }));

                        return retList;

                    }
                }


            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }


        public List<SortingEdgeData> GetSortingEdges(int ScannerId, int PLCNode, out string errMessage)
        {
            List<SortingEdgeData> retList = new List<SortingEdgeData>();
            errMessage = string.Empty;
            try
            {
                if (useCache)
                {
                    return SortingEdges.Value.Where(e => e.ComponentIndexStart == ScannerId && (e.PLCNodeStart??PLCNode)==PLCNode).ToList();
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        retList.AddRange(connection.Query<SortingEdgeData>(@"select *
                                        from vwSortingEdges 
                                        where ComponentIndexStart = @Scanner and isnull(PLCNodeStart, @PLC) = @PLC", new { Scanner = ScannerId, PLC = PLCNode }));

                        return retList;

                    }
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retList;
            }
        }

        public bool SaveItemDestinations(List<ItemDestination> destList, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"update itemdestination set bactive=0 where iditem=@iditem", new { iditem = destList[0].IDItem, desttype = destList[0].DestinationType });

                    foreach (ItemDestination id in destList)
                        connection.Insert<ItemDestination>(id);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }


        public List<TargetSortData> GetSpecialSort(string Sorter, string HDL, string FLC, string specialSort, out string errMessage)
        {
            List<SpecialTargetData> tempList = new List<SpecialTargetData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    tempList = connection.Query<SpecialTargetData>(@"GetSpecialSort", new { Sorter = Sorter, HDL = HDL, FLC = FLC, SpecialSort = specialSort }, commandType: CommandType.StoredProcedure).ToList();

                    return tempList.Select(tl => new TargetSortData() { Sorter = tl.Sorter, Target = tl.Target, TargetTPid = tl.TargetTPid, TargetType = "SPECIAL", Weight = 1,SortingReason=FinalSortReasonEnum.Special, SortingReasonSpecial= specialSort }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }

        private List<vwComponentStatus> GetVwComponentStatuses(List<int> componentIndexes, out string errMessage)
        {
            errMessage = string.Empty;
            List<vwComponentStatus> cs = new List<vwComponentStatus>();

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cs = connection.Query<vwComponentStatus>("select * from vwComponentStatus where idcomponent in @comps", new { comps = componentIndexes }).ToList();
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.ToString();
            }
            return cs;
        }
        /// <summary>
        /// TODO: Refactor to generalize also thinking of dock M1
        /// </summary>
        /// <returns></returns>
        private string EDS1PreferredSorter()
        {
            string sorter = string.Empty;
            string errMessage = string.Empty;
            Dictionary<int, bool> dictValid = new Dictionary<int, bool>();
            Dictionary<string, int> dict = new Dictionary<string, int>();
            dict["VSu"] = 0;
            dict["VSo"] = 0;

            SorterStatus VSu = GetSorterStatus(57000, out errMessage);
            SorterStatus VSo = GetSorterStatus(56000, out errMessage);

            if ((VSu?.Stopped??false) || (VSu?.Standby??false))
                return "A40.VSo";
            else if ((VSo?.Stopped??false) || (VSo?.Standby??false))
                return "A40.VSu";

            List<vwComponentStatus> compstats = GetVwComponentStatuses(new List<int>() { 401, 402, 403, 404, 421, 422, 423, 5001, 5002, 5003, 5004, 5005, 5006 }, out errMessage);
            foreach (vwComponentStatus cs in compstats)
                dictValid[cs.IDComponent] = cs.bCanDischarge;

            //            if (dictValid[401] && dictValid[5001]) dict["VSu"]++;
            if (dictValid[402] && dictValid[5002]) dict["VSu"]++;
            if (dictValid[403] && dictValid[5003]) dict["VSu"]++;
            if (dictValid[404] && dictValid[5004]) dict["VSu"]++;
            //            if (dictValid[421] && dictValid[5001]) dict["VSo"]++;
            if (dictValid[422] && dictValid[5005]) dict["VSo"]++;
            if (dictValid[423] && dictValid[5006]) dict["VSo"]++;

            if (dict["VSu"] < 2 && dict["VSo"] > 0)
                sorter = "A40.VSu";
            else
                sorter = "A40.VSo";

            return sorter;
        }

        public List<TargetSortData> GetAreaSort(Item item, SortingEdgeData TP, string Area, out string errMessage)
        {
            List<TargetSortData> tempList = new List<TargetSortData>();
            string sorter = null;
            errMessage = string.Empty;
            try
            {
                //if (GlobalParameters.ProcessVersion == 1)
                //{
                //    using (var connection = new SqlConnection(connectionstring))
                //    {
                //        return (List<TargetSortData>)connection.Query<TargetSortData>(@"GetAreaTargets", new { TPid = TP.EdgeStartID, AreaName = Area }, commandType: CommandType.StoredProcedure).ToList();
                //    }
                //}
                //if (GlobalParameters.ProcessVersion == 2)
                //{
                using (var connection = new SqlConnection(connectionstring))
                    {

                    if(TP.SortingMethod== "KeepSorter")
                    {
                        sorter = GetItemLastSorter(item, out errMessage);
                    }

                    int TPID = TP.MasterTP.HasValue ? TP.MasterTP.Value : TP.EdgeStartID;

                    //int? snapshot = connection.Query<int>("FM_GetSnapshotToRead", new { elapsedMinutes = 5, TPid = TPID },commandType:CommandType.StoredProcedure).FirstOrDefault();

                    //if (!snapshot.HasValue)
                    //    snapshot = 0;

                    //while (true)
                    //{
                        tempList = connection.Query<TargetSortData>(@"Sorting_GetAreaTargets", new { SnapshotID=0, TPid = TPID, AreaName = Area, SorterPrio = sorter }, commandType: CommandType.StoredProcedure).ToList();

                        //if (!tempList.Any() && snapshot > 0)
                        //{
                        //    snapshot = 0;
                        //    continue;
                        //}

                        tempList = tempList.OrderBy(r => r.Weight).ToList();
                        if (tempList.Any() && !string.IsNullOrEmpty(tempList[0].OutputRule))
                        {
                            string[] parts = tempList[0].OutputRule.Split(',');

                            foreach (string s in parts)
                            {
                                if(s.ToUpper()=="MANAGEEDS1")
                                {
                                    TargetSortData tsd = tempList.Where(t => t.Target == 401 || t.Target == 421).FirstOrDefault();

                                    if (tsd != null)
                                    {
                                        if (tempList.Count > 3)
                                            tempList.Remove(tsd);

                                        string preferredSorter = EDS1PreferredSorter();

                                        if(TP.TPSorterStart!=preferredSorter)
                                        {
                                            tempList.Remove(tsd);
                                        }
                                    }
                                }

                                if (s.ToUpper() == "AVOIDSAME")
                                {
                                    List<int> used = connection.Query<int>(@"select distinct IDDestination
                                                                        from ItemResult 
                                                                        where iditem=@id and IDResultType=1 and IDResult=1 and iddestination in @list", new { id = item.ID, list = tempList.Select(t => t.Target) }).ToList();

                                    List<TargetSortData> newTargetList = new List<TargetSortData>();
                                    newTargetList.AddRange(tempList.Where(tl => !used.Contains(tl.Target)));
                                    if (!newTargetList.Any())
                                        newTargetList.AddRange(tempList.Where(tl => used.Contains(tl.Target)));
                                    tempList.Clear();
                                    tempList.AddRange(newTargetList);

                                }

                                if (s.ToUpper().StartsWith("MAX"))
                                {
                                    int n = int.Parse(s.Substring(3));
                                    tempList = tempList.Take(n).ToList();
                                }

                                if(s.ToUpper()=="BALANCE")
                                {
                                    DataTable tvp = new DataTable();
                                    tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                                    // populate DataTable from your List here
                                    foreach (var id in tempList.Select(ft => ft.Target).Distinct())
                                        tvp.Rows.Add(id);

                                    int chosenTarget = connection.ExecuteScalar<int>("Sorting_BalanceTarget", new { t = tvp.AsTableValuedParameter("IntList") }, commandType: CommandType.StoredProcedure);

                                    List<TargetSortData> newTargetList = new List<TargetSortData>();
                                    newTargetList.AddRange(tempList.Where(tl => tl.Target==chosenTarget));
                                    newTargetList.AddRange(tempList.Where(tl => tl.Target != chosenTarget)); 
                                    tempList.Clear();
                                    tempList.AddRange(newTargetList);
                                }
                            }
                        }
                        return tempList;
                    //}
                    }
                //}
                return tempList;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }
        public List<TargetSortData> TransformEmergencyPlan(List<TargetSortData> targets, ITrackingEvents tracker, out string errMessage)
        {
            List<int> iRet = new List<int>();
            List<TargetSortData> ret = new List<TargetSortData>();
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    //List<int> iTargets = targets.Select(t => t.Target).ToList();
                    foreach(TargetSortData ts in targets)
                    {

                        EmPlanData emtargets = connection.Query<EmPlanData>(@"select  name,targetindexfrom,targetindexto 
                                                                              from vwEmergencyTargets 
                                                                              where targetindexfrom = @id",
                        new { id = ts.Target}).FirstOrDefault();
                        
                        if(emtargets != null)
                        {
                            tracker.WriteMessage(TrackingLevel.Info, "EMPLAN_REPLACE", emtargets.TargetIndexFrom, emtargets.TargetIndexTo, emtargets.Name);
                            ts.Target = emtargets.TargetIndexTo;
                            ts.SortingReason = FinalSortReasonEnum.EmgPlan;
                            ts.SortingReasonSpecial = String.Empty;
                        }
                    }
                }
                return targets;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return targets;
            }
        }

        public bool CompleteIndividualBaggageSort(Item item, bool bFinal, int TPEndId, out string IndSpecialSort, out string errMessage)
        {
            errMessage = string.Empty;
            IndSpecialSort = String.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    GWBaggageExtraction bagextr = connection.Query<GWBaggageExtraction>("select * from BaggageExtraction where id=@id and isnull(bCompleted,0)=0",
                        new { id = item.idIndividualBagSort }).FirstOrDefault();

                    if (bagextr != null)
                    {
                        if (bagextr.idTarget > 0 && bFinal)
                        {
                            bagextr.bCompleted = true;
                        }
                        else if (bagextr.idTarget < 0)
                        {
                            List<string> Policies = connection.Query<string>("	SELECT distinct policy FROM dbo.vwSortingEdges WHERE EdgeEndID=@tpid", new { tpid = TPEndId }).ToList();

                            BaggageExtractionArea bae = connection.Query<BaggageExtractionArea>("select * from BaggageExtractionArea where ID=@a", new { a = bagextr.idTarget.Value }).FirstOrDefault();
                            if (Policies.Contains(bae.AreaPolicy))
                            {
                                bagextr.bCompleted = true;
                                IndSpecialSort = bagextr.idTarget.ToString();
                            }
                        }
                        else if (!string.IsNullOrEmpty(bagextr.SpecialSort) && bFinal)
                        {
                            bagextr.bCompleted = true;
                            IndSpecialSort = bagextr.SpecialSort;
                        }
                            

                        connection.Update<GWBaggageExtraction>(bagextr);
                        return bagextr.bCompleted??false;
                    }
                    else errMessage = "NOINDIVIDUAL";
                    return false;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }
        public List<TargetSortData> CheckIndividualBaggageSort(SortingEdgeData TP, BSMData BSM, Item item, out string errMessage)
        {
            errMessage = string.Empty;
            List<TargetSortData> finsort = new List<TargetSortData>();

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    GWBaggageExtraction bagextr = connection.Query<GWBaggageExtraction>("select * from BaggageExtraction where idbsm=@id and isnull(bCompleted,0)=0",
                        new { id = BSM.ID }).FirstOrDefault();

                    if(bagextr != null)
                    {
                        if (!string.IsNullOrEmpty(bagextr.SpecialSort))
                        {
                            finsort = GetSpecialSort(null, null, null, bagextr.SpecialSort, out errMessage);
                            if(finsort.Any())
                            {
                                finsort[0].SortingReason = FinalSortReasonEnum.Individual;
                                finsort[0].SortingReasonSpecial = bagextr.SpecialSort;
                            }
                        }
                        else
                        {
                            if (bagextr.idTarget.HasValue && bagextr.idTarget.Value < 0)
                            {
                                BaggageExtractionArea bae = connection.Query<BaggageExtractionArea>("select * from BaggageExtractionArea where ID=@a", new { a = bagextr.idTarget.Value }).FirstOrDefault();

                                if (bae != null)
                                {
                                    finsort = GetAreaSort(item, TP, bae.AreaPolicy, out errMessage);
                                    if (finsort.Any())
                                    {
                                        finsort[0].SortingReason = FinalSortReasonEnum.Individual;
                                        finsort[0].SortingReasonSpecial = bae.AreaPolicy;
                                    }
                                }
                            } 
                            else
                            {
                                finsort = new List<TargetSortData>() { new TargetSortData() { bOverridable = false,Target=bagextr.idTarget.Value,TargetType="FINAL" } };
                                if (finsort.Any())
                                {
                                    finsort[0].SortingReason = FinalSortReasonEnum.Individual;
                                }
                            }
                        }


                        if(finsort.Any())
                        {
                            connection.Execute(@"UPDATE Item set idIndividualBagSort=@id where id=@iditem", new { id = bagextr.ID, iditem = item.ID });
                            item.idIndividualBagSort = bagextr.ID;
                        }

                        bagextr.idItem = item.ID;
                        connection.Update<GWBaggageExtraction>(bagextr);
                    } 
                    return finsort;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }
        public List<RecirculationCount> GetItemRecirculationCount(int idPLC, Item item, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return  connection.Query<RecirculationCount>("Sort_GetRecalculationCount",
                        new { IDPLC=idPLC, IDItem = item.ID }, commandType: CommandType.StoredProcedure).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<RecirculationCount>();
            }

        }

        public List<TargetSortData> TestGetAreaTargets(int TPID, string TPSorter, string sorter, string area, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                // get the valid snapshot
                using (var connection = new SqlConnection(connectionstring))
                {
                    List<TargetSortData> tempList =  connection.Query<TargetSortData>(@"Sorting_GetAreaTargets", new { SnapshotID = 0, TPid = TPID, AreaName = area, SorterPrio = sorter }, commandType: CommandType.StoredProcedure).ToList();

                    tempList = tempList.OrderBy(r => r.Weight).ToList();
                    if (tempList.Any() && !string.IsNullOrEmpty(tempList[0].OutputRule))
                    {
                        string[] parts = tempList[0].OutputRule.Split(',');

                        foreach (string s in parts)
                        {
                            if (s.ToUpper() == "MANAGEEDS1")
                            {
                                TargetSortData tsd = tempList.Where(t => t.Target == 401 || t.Target == 421).FirstOrDefault();

                                if (tsd != null)
                                {
                                    string preferredSorter = EDS1PreferredSorter();

                                    if (TPSorter != preferredSorter)
                                    {
                                        tempList.Remove(tsd);
                                    }
                                }
                            }

                            //if (s.ToUpper() == "AVOIDSAME")
                            //{
                            //    List<int> used = connection.Query<int>(@"select distinct IDDestination
                            //                                            from ItemResult 
                            //                                            where iditem=@id and IDResultType=1 and IDResult=1 and iddestination in @list", new { id = item.ID, list = tempList.Select(t => t.Target) }).ToList();

                            //    List<TargetSortData> newTargetList = new List<TargetSortData>();
                            //    newTargetList.AddRange(tempList.Where(tl => !used.Contains(tl.Target)));
                            //    if (!newTargetList.Any())
                            //        newTargetList.AddRange(tempList.Where(tl => used.Contains(tl.Target)));
                            //    tempList.Clear();
                            //    tempList.AddRange(newTargetList);

                            //}

                            if (s.ToUpper().StartsWith("MAX"))
                            {
                                int n = int.Parse(s.Substring(3));
                                tempList = tempList.Take(n).ToList();
                            }

                            if (s.ToUpper() == "BALANCE")
                            {
                                DataTable tvp = new DataTable();
                                tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                                // populate DataTable from your List here
                                foreach (var id in tempList.Select(ft => ft.Target).Distinct())
                                    tvp.Rows.Add(id);

                                int chosenTarget = connection.ExecuteScalar<int>("Sorting_BalanceTarget", new { t = tvp.AsTableValuedParameter("IntList") }, commandType: CommandType.StoredProcedure);

                                List<TargetSortData> newTargetList = new List<TargetSortData>();
                                newTargetList.AddRange(tempList.Where(tl => tl.Target == chosenTarget));
                                newTargetList.AddRange(tempList.Where(tl => tl.Target != chosenTarget));
                                tempList.Clear();
                                tempList.AddRange(newTargetList);
                            }
                        }
                    }
                    return tempList;

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }






        }


        public List<TargetSortData> TestGetIntermediateTargets(int TPID, string sorter, List<int> targets, out string errMessage)
        {
            errMessage = string.Empty;
            List<TargetSortData> ret = new List<TargetSortData>();

            DataTable tvp = new DataTable();
            tvp.Columns.Add(new DataColumn("ID", typeof(int)));

            try
            {
                // get the valid snapshot
                using (var connection = new SqlConnection(connectionstring))
                {
                    // populate DataTable from your List here
                    foreach (int id in targets)
                        tvp.Rows.Add(id);
                    ret = connection.Query<TargetSortData>("dbo.sorting_GetIntermediateTargets",
                     new
                     {
                         IDSnapshot = 0,
                         TPid = TPID,
                         FinalTargets = tvp.AsTableValuedParameter("IntList"),
                         SorterPrio = sorter
                     }, commandType: CommandType.StoredProcedure).ToList();

                    return ret.OrderBy(r => r.Weight).ToList();
                }
            }
            catch(Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }




        public List<TargetSortData> GetIntermediateTargets(Item item, SortingEdgeData TP, List<TargetSortData> FinalTargets, out string errMessage)
        {
            List<TargetSortData> ret = new List<TargetSortData>();
            string sorter = null;
            errMessage = string.Empty;
            bool bOrdered = (bool)(GlobalConf["ORDERED_INTERMEDIATE"] ?? false);
            try
            {
                // get the valid snapshot
                using (var connection = new SqlConnection(connectionstring))
                {

                if (TP.SortingMethod == "KeepSorter")
                {
                    sorter = GetItemLastSorter(item, out errMessage);
                }

                int TPID = TP.MasterTP.HasValue ? TP.MasterTP.Value : TP.EdgeStartID;


                    if (!bOrdered)
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in FinalTargets.Select(ft => ft.Target).Distinct())
                            tvp.Rows.Add(id);
                        ret = connection.Query<TargetSortData>("dbo.sorting_GetIntermediateTargets",
                         new { IDSnapshot = 0, TPid = TPID, FinalTargets = tvp.AsTableValuedParameter("IntList"), SorterPrio = sorter }, commandType: CommandType.StoredProcedure).ToList();
                    }
                    else
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));
                        tvp.Columns.Add(new DataColumn("ORDER", typeof(int)));

                        // populate DataTable from your List here
                        int o = 1;
                        foreach (var id in FinalTargets.Select(ft => ft.Target).Distinct())
                        {
                            DataRow dr = tvp.NewRow();

                            dr[0] = id;
                            dr[1] = o;
                            tvp.Rows.Add(dr);
                            o++;
                        }
                        ret = connection.Query<TargetSortData>("dbo.sorting_GetOrderedIntermediateTargets",
                        new { IDSnapshot = 0, TPid = TPID, FinalTargets = tvp.AsTableValuedParameter("OrderedIntList"), SorterPrio = sorter }, commandType: CommandType.StoredProcedure).ToList();
                    }



                    ret = ret.OrderBy(r => r.Weight).ToList();

                    if (ret.Any() && !string.IsNullOrEmpty(ret[0].OutputRule))
                    {
                        string[] parts = ret[0].OutputRule.Split(',');

                        foreach (string s in parts)
                        {
                            if (s.ToUpper() == "BALANCE")
                            {
                                DataTable tvp2 = new DataTable();
                                tvp2.Columns.Add(new DataColumn("ID", typeof(int)));

                                // populate DataTable from your List here
                                foreach (var id in ret.Select(ft => ft.Target).Distinct())
                                    tvp2.Rows.Add(id);

                                int chosenTarget = connection.ExecuteScalar<int>("Sorting_BalanceTarget", new { t = tvp2.AsTableValuedParameter("IntList") }, commandType: CommandType.StoredProcedure);

                                List<TargetSortData> newTargetList = new List<TargetSortData>();
                                newTargetList.AddRange(ret.Where(tl => tl.Target == chosenTarget));
                                newTargetList.AddRange(ret.Where(tl => tl.Target != chosenTarget));
                                ret.Clear();
                                ret.AddRange(newTargetList);
                            }
                        }
                    }
                    return ret;

                }
                return ret;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return ret;
            }
        }

        public string GetItemLastSorter(Item i, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<string>(@"SELECT TOP(1) pc.name 
                                                    FROM dbo.ItemResult ir
                                                     JOIN component c ON c.ComponentIndex=ir.IDDestination
                                                    JOIN component pc ON c.IDParentComponent=pc.id
													join componenttype ct on ct.id=pc.IDComponentType
                                                    WHERE ir.IDResultType=1 AND ir.IDResult=1 AND ir.iditem=@iditem and ct.name='Sorter'
                                                    ORDER BY ir.Timestamp desc", new { iditem = i.ID });
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }



        }

        public List<OnlineSortingDestinations> GetSortingDestinationsByIDs(List<int> dailySortDestIdList, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    string sql = $"select distinct * from vwonlinesortinginfo where IDDestination in @idlist";

                    List<OnlineSortingDestinations> dest = connection.Query<OnlineSortingDestinations>(sql,
                        new { idlist= dailySortDestIdList}).ToList();

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<OnlineSortingDestinations>();
            }
        }

        public List<MergeRedirectInfo> UpdateMergeRedirectStatus(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    List<MergeRedirect> updatedMR = connection.Query<MergeRedirect>(@"update mergeredir set mergeredir.active=m.newActive
                                                                                output inserted.*
                                                                                from mergeredirect mergeredir
                                                                                join (select mr.id,active oldActive, case when dsf.OpenStatus='' or dsf.OpenStatus='Closed' then 1 else 0 end newActive
                                                                                from mergeredirect mr
                                                                                    join vwDailySortingFlights dsf on dsf.id = mr.idOriginalFlight) m on m.id=mergeredir.id and m.oldactive!=m.newactive").ToList();

                    if (updatedMR.Any())
                        return connection.Query<MergeRedirectInfo>(@"select ds.id idDailySorting,ds.Name,ds.Published, mr.idOriginalFlight,df1.flight Flight1, dsf1.SDT SDT1,mr.idTargetFlight,df1.flight Flight2, dsf2.SDT SDT2
                                                                    from mergeredirect mr
                                                                    join dailysortingflights dsf1 on mr.[idOriginalFlight]=dsf1.id
                                                                    join dailyflights df1 on df1.id=dsf1.[IDFlights]
                                                                    join dailysorting ds on dsf1.iddailysorting=ds.id
                                                                    join dailysortingflights dsf2 on mr.[idTargetFlight]=dsf2.id
                                                                    join dailyflights df2 on df2.id=dsf2.[IDFlights]
                                                                    where mr.id in @ids", new { ids = updatedMR.Select(r => r.ID) }).ToList();
                    else
                        return new List<MergeRedirectInfo>();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<MergeRedirectInfo>();
            }
        }


        public List<OnlineSortingDestinations> GetDayBeforeNotClosedDestinations(out string errMessage)
        {
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                        string sql = $"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus in ('Open','Closing') and sdt=CAST(DATEADD(DAY,-1,GETDATE()) AS DATE)";

                        dest = connection.Query<OnlineSortingDestinations>(sql).ToList();

                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = "Closed"}, commandType: CommandType.StoredProcedure, commandTimeout:0);

                        connection.Execute("update configGlobal set value=@val where name=@name", new { name = "AUTOMATIC_CLOSE_LASTDATE", val = DateTime.Now.ToShortDateString() });
                    }

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return dest;
            }


        }



        public List<OnlineSortingDestinations> ChangeDestinationStatusFast(DateTime dt, string CurrentStatus, string NewStatus, out string errMessage)
        {
            return ChangeDestinationStatusFast(dt, null, CurrentStatus, NewStatus, out errMessage);
        }

        public List<OnlineSortingDestinations> CloseTargets(int targetComponentIndex, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    List<int> targets = connection.Query<int>(@"SELECT dsd.id
                                                                FROM dbo.DailySortingDestinations dsd
                                                                JOIN dbo.DailySortingFlights dsf ON dsd.IDDailySortingFlights=dsf.id
                                           
                                                                                                            where dsd.SortDestination=@tgt
                                                                                                            and dsd.idOpenStatus='Closing'
                                                                                                            and dsf.sdt=cast(getdate() as date)", new { tgt = targetComponentIndex }).ToList();

                    connection.Execute(@"update DailySortingDestinations 
                                         set idOpenStatus='Closed',ClosedTSManual=getdate()
                                         where id in @IDS", new { IDS = targets });





                    return connection.Query<OnlineSortingDestinations>(@"select * from vwOnlineSortingInfo where idDestination in @IDS", new { IDS = targets }).ToList();
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<OnlineSortingDestinations>();
            }
        }

        public List<OnlineSortingDestinations> ReopenClosedTargets(DateTime dt, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    dest = connection.Query<OnlineSortingDestinations>(@"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus='Closed' and (Closingts>@currDate or closedts>@currdate)",
                        new { currDate = dt }).ToList();

                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = "Open"}, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }
            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: {conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }

        public List<OnlineSortingDestinations> ReopenClosedTargetsFast(DateTime dt, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    string sql = @$"select dsd.id IDDestination,t.[Target],t.Sorter,ds.id SortplanId, ds.name SortplanName, df.flight,df.sdt 
                                        from dailysortingdestinations dsd
                                        join dailysortingflights dsf on dsd.IDDailySortingFlights=dsf.id
                                        join DailySorting ds on ds.id=dsf.IDDailySorting
                                        join DailyFlights df on df.id=dsf.IDFlights
                                        join (select componentindex,[Target],STRING_AGG(sorter,',') Sorter
                                        from (select distinct c.componentindex,c.name Target, c2.name Sorter
                                        from component c
                                        join component c2 on c.IDParentComponent =c2.id
                                        join componenttype ct on c.IDComponentType=ct.id
                                        where ct.name='Trg') a
                                        group by ComponentIndex,Target) t on t.componentindex=dsd.SortDestination
                                        where ds.Published=1 
                                        and dsd.idopenstatus='Closed' and (isnull(dsd.ClosingTsManual,dsd.Closingts)>@currDate 
                                        or isnull(dsd.closedtsManual,dsd.ClosedTs)>@currdate)";

                    dest = connection.Query<OnlineSortingDestinations>(sql,
                        new { currDate = dt }).ToList();

                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = "Open" }, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }
            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: {conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }


        public List<OnlineSortingDestinations> NotOpenOpenTargetsFast(DateTime dt, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    string sql = @$"select dsd.id IDDestination,t.[Target],t.Sorter,ds.id SortplanId, ds.name SortplanName, df.flight,df.sdt 
                                        from dailysortingdestinations dsd
                                        join dailysortingflights dsf on dsd.IDDailySortingFlights=dsf.id
                                        join DailySorting ds on ds.id=dsf.IDDailySorting
                                        join DailyFlights df on df.id=dsf.IDFlights
                                        join (select componentindex,[Target],STRING_AGG(sorter,',') Sorter
                                        from (select distinct c.componentindex,c.name Target, c2.name Sorter
                                        from component c
                                        join component c2 on c.IDParentComponent =c2.id
                                        join componenttype ct on c.IDComponentType=ct.id
                                        where ct.name='Trg') a
                                        group by ComponentIndex,Target) t on t.componentindex=dsd.SortDestination
                                        where ds.Published=1 
                                        and idopenstatus in ('Open','Closing') and isnull(OpenTsManual,Opents)>@currDate";

                    dest = connection.Query<OnlineSortingDestinations>(sql, new { currDate = dt }).ToList();

                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = "NotOpen" }, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: {conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }

        public List<OnlineSortingDestinations> NotOpenOpenTargets(DateTime dt, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    dest = connection.Query<OnlineSortingDestinations>(@"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus in ('Open','Closing') and Opents>@currDate",
                        new { currDate = dt }).ToList();

                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = "NotOpen" }, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: {conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }

        public List<OnlineSortingDestinations> ChangeDestinationStatus(DateTime dt, string CurrentStatus, string NewStatus, out string errMessage)
        {
            return ChangeDestinationStatus(dt, null, CurrentStatus, NewStatus, out errMessage);
        }

        public List<OnlineSortingDestinations> ChangeDestinationStatus(DateTime dt, int? Target, string CurrentStatus, string NewStatus, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    if (Target.HasValue)
                    {
                        string sql = $"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus=@currStatus and {NewStatus}ts<=@currDate and targetComponentIndex=@tgt";

                        dest = connection.Query<OnlineSortingDestinations>(sql,
                            new { currStatus = CurrentStatus, currDate = dt, tgt = Target }).ToList();
                    }
                    else
                    {
                        string sql = $"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus=@currStatus and {NewStatus}ts<=@currDate";

                        dest = connection.Query<OnlineSortingDestinations>(sql,
                            new { currStatus = CurrentStatus, currDate = dt }).ToList();
                    }
                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (var id in dest.Select(dst => dst.IDDestination).Distinct())
                            tvp.Rows.Add(id);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = NewStatus }, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: {conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }


        public List<OnlineSortingDestinations> ChangeDestinationStatusFast(DateTime dt, int? Target, string CurrentStatus, string NewStatus, out string errMessage)
        {
            errMessage = string.Empty;
            List<OnlineSortingDestinations> dest = new List<OnlineSortingDestinations>();
            int conntmo = 0;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    if (Target.HasValue)
                    {
                        //string sql = $"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus=@currStatus and {NewStatus}ts<=@currDate and targetComponentIndex=@tgt";
                        string sql = @$"select dsd.id IDDestination,t.[Target],t.Sorter,ds.id SortplanId, ds.name SortplanName, df.flight,df.sdt 
                                        from dailysortingdestinations dsd
                                        join dailysortingflights dsf on dsd.IDDailySortingFlights=dsf.id
                                        join DailySorting ds on ds.id=dsf.IDDailySorting
                                        join DailyFlights df on df.id=dsf.IDFlights
                                        join (select componentindex,[Target],STRING_AGG(sorter,',') Sorter
                                        from (select distinct c.componentindex,c.name Target, c2.name Sorter
                                        from component c
                                        join component c2 on c.IDParentComponent =c2.id
                                        join componenttype ct on c.IDComponentType=ct.id
                                        where ct.name='Trg') a
                                        group by ComponentIndex,Target) t on t.componentindex=dsd.SortDestination
                                        where ds.Published=1 
                                        and dsd.idopenstatus=@currStatus and isnull(dsd.{NewStatus}tsManual,dsd.{NewStatus}ts)<=@currDate
                                        and dsd.SortDestination=@tgt";

                        dest = connection.Query<OnlineSortingDestinations>(sql,
                            new { currStatus = CurrentStatus, currDate = dt, tgt = Target }).ToList();
                    }
                    else
                    {
                        //string sql = $"select distinct * from vwonlinesortinginfo with(nolock) where idopenstatus=@currStatus and {NewStatus}ts<=@currDate";
                        string sql = @$"select dsd.id IDDestination,t.[Target],t.Sorter,ds.id SortplanId, ds.name SortplanName, df.flight,df.sdt 
                                        from dailysortingdestinations dsd
                                        join dailysortingflights dsf on dsd.IDDailySortingFlights=dsf.id
                                        join DailySorting ds on ds.id=dsf.IDDailySorting
                                        join DailyFlights df on df.id=dsf.IDFlights
                                        join (select componentindex,[Target],STRING_AGG(sorter,',') Sorter
                                        from (select distinct c.componentindex,c.name Target, c2.name Sorter
                                        from component c
                                        join component c2 on c.IDParentComponent =c2.id
                                        join componenttype ct on c.IDComponentType=ct.id
                                        where ct.name='Trg') a
                                        group by ComponentIndex,Target) t on t.componentindex=dsd.SortDestination
                                        where ds.Published=1 
                                        and dsd.idopenstatus=@currStatus and isnull(dsd.{NewStatus}tsManual,dsd.{NewStatus}ts)<=@currDate";

                        dest = connection.Query<OnlineSortingDestinations>(sql,
                            new { currStatus = CurrentStatus, currDate = dt }).ToList();
                    }
                    if (dest.Any())
                    {
                        DataTable tvp = new DataTable();
                        tvp.Columns.Add(new DataColumn("ID", typeof(int)));

                        // populate DataTable from your List here
                        foreach (OnlineSortingDestinations id in dest)
                            tvp.Rows.Add(id.IDDestination);
                        conntmo = connection.ConnectionTimeout;

                        connection.Execute("[dbo].[UpdateDestinationStatus]",
                            new { DestIds = tvp.AsTableValuedParameter("IntList"), NewStatus = NewStatus }, commandType: CommandType.StoredProcedure);
                    }

                    return dest;
                }

            }
            catch (Exception ex)
            {
                errMessage = $"Timeout: { conntmo} " + ex.CompleteMessage();
                return dest;
            }
        }


        public bool UpdateCharts(out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("BsmCheck_UpdateCharts", commandType: CommandType.StoredProcedure);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool UpdateBsmCheck(out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("BsmCheck_UpdateTable", commandType: CommandType.StoredProcedure);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }




        public string GenControlId()
        {
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<string>("generatecontrolid", commandType: CommandType.StoredProcedure);
                }
            }
            catch (Exception ex)
            {
                return ex.CompleteMessage();
            }
        }

        public bool GetAlarmStatus(string Alarm, out string errMessage)
        {
            errMessage = string.Empty;
            bool ret = false;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    ret =  connection.ExecuteScalar<bool>(@"SELECT case when state= 'ON' then cast(0 as bit) else cast(1 as bit) end AlarmStatus " +
                        "                                  FROM SACBASE.DBO.GUIAlarms " +
                        "                                  where alarmid = @alm", new {alm=Alarm});

                    return ret;
                }
            }
            catch (Exception ex)
            {
                errMessage= ex.CompleteMessage();
                return ret;
            }





        }


        public SACNotificationAlarm GetAOSConnectionAlarm(int timeLimit, out string errMessage)
        {
            errMessage = string.Empty;
            int timeRec = 0;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "AODBConnection", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    bool AOSStatus = connection.ExecuteScalar<bool>("select status from SACINTChannelDatasourceStatus where datasource='AOSStatus'");

                    if (!AOSStatus)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }


        public SACNotificationAlarm GetAODBConnectionAlarm(int timeLimit, out string errMessage)
        {
            errMessage = string.Empty;
            int timeRec = 0;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "AODBConnection", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    timeRec = connection.ExecuteScalar<int>(@"select isnull(max(datediff(second,timestamp,getdate())),0) from AODBInputMessages where ProcessStatus='NotProcessed'");


                    if (timeRec > timeLimit)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetBMConnectionAlarm(int timeLimit, out string errMessage)
        {
            errMessage = string.Empty;
            int timeRecLH = 0;
            int timeRecSITA = 0;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "BagMessageConnection", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    bool LHStatus = connection.ExecuteScalar<bool>("select status from SACINTChannelDatasourceStatus where datasource='LHStatus'");
                    bool SITAStatus = connection.ExecuteScalar<bool>("select status from SACINTChannelDatasourceStatus where datasource='SitaStatus'");


                    //timeRecLH = connection.ExecuteScalar<int>(@"select isnull(max(datediff(second,timestamp,getdate())),0) from BSMQueue where ProcessStatus='NotProcessed' and addressstamp like 'LH%'");

                    //timeRecSITA = connection.ExecuteScalar<int>(@"select isnull(max(datediff(second,timestamp,getdate())),0) from BSMQueue where ProcessStatus='NotProcessed' and addressstamp not like 'LH%'");


                    //if (timeRecLH > timeLimit)
                    //    connection.Execute(@"update SACINTChannelDatasourceStatus set Status=0,StatusUpdateTimestamp=getdate() where datasource='LHStatus'");
                    //if (timeRecSITA > timeLimit)
                    //    connection.Execute(@"update SACINTChannelDatasourceStatus set Status=0,StatusUpdateTimestamp=getdate() where datasource='SitaStatus'");

                    //if (timeRecLH > timeLimit || timeRecSITA > timeLimit)


                    if(!LHStatus || !SITAStatus)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }



        public SACNotificationAlarm GetGFAConnectionAlarm(int timeLimit, out string errMessage)
        {
            errMessage = string.Empty;
            int timeRec = 0;
            int timeSent = 0;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "GFAConnection", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    timeRec = connection.ExecuteScalar<int>(@"select isnull(datediff(second,max(rec.timestamp),getdate()),10000) 
                                                                from GFAReceivedMessages rec
                                                                where rec.messagetype='OperationalState'");

                    timeSent = connection.ExecuteScalar<int>(@"select isnull(datediff(second,max(sent.timestamp),getdate()),10000) 
                                                                from GFASentMessages sent
                                                                where sent.messagetype='OPERATIONAL'");

                    if (timeRec > timeLimit || timeSent > timeLimit)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetNoBSMAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "NoBSM", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>(@"select count(*) from item where LastUpdateTimestamp >dateadd(minute,-@min,getdate()) and BagDestinationStatus='NoBsm'", new { min = timeWindow.Value });

                    if (cnt>maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetNoBSMAlarmBSMCheck( out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "NoBSM", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>(@"select count(bc.id)
                                                            from bsmcheck bc
                                                            join DailyFlights df on bc.idDailyFlights=df.id
                                                            where df.sdt=cast(getdate() as date) and bc.bProblem=1");

                    if (cnt > 0)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetBSMQueueAlarmBSMCheck(int Threshold, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "BSMQueue", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>(@"select count(*) from bsmqueue where timestamp<dateadd(second,-2,getdate()) and bprocessed=0 and  bprocessing=0");

                    if (cnt > Threshold)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }


        public SACNotificationAlarm GetNoFlightAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "NoFlight", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>(@"select count(*) from item where LastUpdateTimestamp >cast(getdate() as date) and BagDestinationStatus='NoFlight'");


                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetFlightUpdateAlarm(out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "FlightUpdate", State = "OFF" };

            try
            {
                if (string.IsNullOrEmpty(SACBaseConnectionString))
                    return alm;

                using (var connection = new SqlConnection(SACBaseConnectionString))
                {
                    cnt = connection.ExecuteScalar<int>(@"SELECT COUNT(*) FROM dbo.GUIMessages WHERE modulename='AODBProcWorker' AND IsValid=1 AND GETDATE()<ExpireTime");


                    if (cnt > 0)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetHBSEmergencyAlarm(bool HBSEmergencyMode, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "HBSEmergency", State = "OFF" };

            try
            {
                    if (HBSEmergencyMode)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }




        public SACNotificationAlarm GetNoAllocationAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "NoAllocation", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    if (!timeWindow.HasValue)
                    {
                        cnt = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >cast(getdate() as date) 
                                                        and BagDestinationStatus='NoAllocation'");
                    }
                    else
                    {
                        cnt = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >dateadd(minute,-@tw,getdate()) 
                                                        and BagDestinationStatus='NoAllocation'", new { tw = timeWindow });
                    }

                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetNoFlightOrAllocationAlarm(int? timeWindowNoFlight, int? timeWindowNoAllocation, int maxNumberNoFlight, int maxNumberNoAllocation, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt1;
            int cnt2;
            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "NoAllocation", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    if (!timeWindowNoFlight.HasValue)
                    {
                        cnt2 = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >cast(getdate() as date) 
                                                        and BagDestinationStatus='NoFlight'");
                    }
                    else
                    {
                        cnt2 = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >dateadd(minute,-@tw,getdate()) 
                                                        and BagDestinationStatus='NoFlight'", new { tw = timeWindowNoFlight });
                    }

                    if (!timeWindowNoAllocation.HasValue)
                    {
                        cnt1 = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >cast(getdate() as date) 
                                                        and BagDestinationStatus='NoAllocation'");
                    }
                    else
                    {
                        cnt1 = connection.ExecuteScalar<int>(@"select count(*) 
                                                        from item 
                                                        where LastUpdateTimestamp >dateadd(minute,-@tw,getdate()) 
                                                        and BagDestinationStatus='NoAllocation'", new { tw = timeWindowNoAllocation });
                    }

                    if (cnt1 > maxNumberNoAllocation || cnt2 > maxNumberNoFlight)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }


        public SACNotificationAlarm GetRecirculationSorterAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "RecirculationError", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    if(timeWindow.HasValue)
                    {
                        cnt = connection.ExecuteScalar<int>(@"select count(*) from bagtracking 
                                                        where SortingReason in ('AlternativeFault','AlternativeBlock')
                                                        and timestamp >dateadd(minute,-@tw,getdate())", new { tw = timeWindow });

                    }
                    else
                    {
                        cnt = connection.ExecuteScalar<int>(@"select count(*) from bagtracking 
                                                        where SortingReason in ('AlternativeFault','AlternativeBlock')
                                                        and cast(timestamp as date)=cast(GetDate() as date)");

                    }



                    //                  cnt = connection.ExecuteScalar<int>(@"SELECT COUNT(iditem)
                    //FROM (SELECT ir.iditem,ir.IDDestination,c.IDParentComponent,COUNT(*) cnt
                    //FROM itemresult ir
                    //JOIN dbo.Component c ON ir.IDDestination=c.ComponentIndex
                    //WHERE ir.IDResult=3 AND ir.IDResultType=1 and ir.timestamp>cast(getdate() as date)
                    //GROUP BY ir.iditem,ir.IDDestination,c.IDParentComponent) rec
                    //JOIN dbo.SorterRecirculations srec ON rec.IDParentComponent=srec.IDSorter AND rec.cnt>srec.RecirculationThreshold");

                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }



        
        public SACNotificationAlarm GetItemRecirculationSorterAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "RecirculationError", State = "OFF" };

            try
            {
                int maxRecirc = (int)(GlobalConf["SORTER_MAX_RECIRCULATIONS_ITEM"] ?? 10);

                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>(@"select count(*) from (
                                                            select ir.IDItem
                                                            from dbo.ItemResult ir
                                                            join dbo.SortResultEnum sr on sr.ID = ir.IDResult
                                                            join dbo.Component c on c.ComponentIndex = ir.IDDestination
                                                            join dbo.ComponentType ct on ct.ID = c.IDComponentType
                                                            where sr.bValid = 0
                                                              and ct.Name in ('Trg','Outj')
                                                              and ir.Timestamp > case when @tw is null
                                                                                      then cast(getdate() as date)
                                                                                      else dateadd(minute,-@tw,getdate()) end
                                                            group by ir.IDItem
                                                            having count(*) > @maxRecirc) bags",
                                                        new { tw = timeWindow, maxRecirc });

                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }


        public SACNotificationAlarm GetRecirculationAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "RecirculationError", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    int maxRec = (int)(GlobalConf["MAXRECIRCULATION"] ?? 3);

                    cnt = connection.ExecuteScalar<int>(@"SELECT COUNT(*) cntItem FROM 
                                                            (SELECT iditem,COUNT(*) cnt
                                                            FROM dbo.ItemResult
                                                            WHERE idresult=1 AND Timestamp>CAST(GETDATE() AS DATE)
                                                            GROUP BY IDItem 
                                                            HAVING COUNT(*) > @max) a", new { max = maxRec });

                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public SACNotificationAlarm GetTransportTimeAlarm(int? timeWindow, int maxNumber, out string errMessage)
        {
            errMessage = string.Empty;
            int cnt;

            SACNotificationAlarm alm = new SACNotificationAlarm() { AlarmID = "TransportTimeAlarm", State = "OFF" };

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    cnt = connection.ExecuteScalar<int>("SELECT COUNT(*) FROM dbo.vwTransportTimeMonitoring WHERE timestampend>CAST(GETDATE() AS DATE) AND bstatus=0");

                    if (cnt > maxNumber)
                        alm.State = "ON";
                    else
                        alm.State = "OFF";

                    return alm;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return alm;
            }
        }

        public bool SetBSMItemAttributes(int idBSM, long idItem, string lastLocation, string SecurityStatus, string ControlId, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE bsms set IDItem=@i,lastLocation=@lastloc,ControlId=@kid,securityStatus=@secstatus  where id=@idbsm", 
                        new { i = idItem, lastloc=lastLocation, secstatus=SecurityStatus, kid=ControlId, idbsm = idBSM });

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool SetBSMItem(int idBsm, int idItem, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE bsms set IDItem=@i where id=@idbsm",new { i = idItem, idbsm = idBsm });
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool SetItemBSM(int idBsm, int idItem, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE item set idBSM=@idbsm where ID=@i", new { i = idItem, idbsm = idBsm });
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool SetBSMControlId(int idBsm, string ControlId, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE bsms set ControlId=@kid where idbsm=@idbsm", new { kid = ControlId, idbsm = idBsm });
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public string  GettBSMControlId(int idBsm, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<string>("select ControlId from bsms where idbsm=@idbsm", new { idbsm = idBsm });
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return string.Empty;
            }
        }

        public List<TargetSortData> GetSecurityTargets(SortingEdgeData TP, string SecurityStatus, out string errMessage)
        {
            errMessage = string.Empty;
            List<TargetSortData> targets = null;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    targets = connection.Query<SortingEdgeData>(@"select *
                                                        from vwsortingedges
                                                        where edgestartid=@TPid 
                                                        and securitystatus=@status", new { TPid=TP.EdgeStartID, status = SecurityStatus})
                        .Select(t=> new TargetSortData() { bOverridable = false, Sorter = t.TPSorterEnd, Target = t.ComponentIndexEnd, TargetTPid = t.EdgeEndID })
                        .ToList();

                    if(targets?.Any()!=true)
                        targets = connection.Query<SortingEdgeData>(@"select *
                                                        from vwsortingedges
                                                        where edgestartid=@TPid 
                                                        and securitystatus is null", new { TPid = TP.EdgeStartID })
                                            .Select(t => new TargetSortData() { bOverridable = false, Sorter = t.TPSorterEnd, Target = t.ComponentIndexEnd, TargetTPid = t.EdgeEndID })
                                            .ToList();
                    return targets;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }

        public List<TargetSortData> GetSecurityTargetsBool(SortingEdgeData TP, string SecurityStatus, out string errMessage)
        {
            errMessage = string.Empty;
            List<TargetSortData> targets = null;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    bool secStatus = connection.Query<SecurityStatusLookup>(@"select *
                                                        from SecurityStatusLookup
                                                        where securitystatusFromPLC=@status", new {status = SecurityStatus })
                        .Select(t => t.bSecurityStatus).SingleOrDefault();

                    targets = connection.Query<SortingEdgeData>(@"select *
                                                        from vwsortingedges
                                                        where edgestartid=@TPid 
                                                        and bsecuritystatus=@status", new { TPid = TP.EdgeStartID, status = secStatus })
                        .Select(t => new TargetSortData() { bOverridable = false, Sorter = t.TPSorterEnd, Target = t.ComponentIndexEnd, TargetTPid = t.EdgeEndID })
                        .ToList();

                    //if (targets?.Any() != true)
                    //    targets = connection.Query<SortingEdgeData>(@"select *
                    //                                    from vwsortingedges
                    //                                    where edgestartid=@TPid 
                    //                                    and securitystatus is null", new { TPid = TP.EdgeStartID })
                    //                        .Select(t => new TargetSortData() { bOverridable = false, Sorter = t.TPSorterEnd, Target = t.ComponentIndexEnd, TargetTPid = t.EdgeEndID })
                    //                        .ToList();
                    return targets;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetSortData>();
            }
        }

        public bool CheckMergeRedirect(int idDailySortingFylight, out int? idDailyFlightsNew, out string MergeRedirectType, out string errMessage)
        {
            idDailyFlightsNew = null;
            MergeRedirectType = string.Empty;
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    var mr = connection.Query(@"SELECT dsf.IDFlights,mr.RedirectType
                                            FROM dbo.MergeRedirect mr
                                            JOIN dbo.DailySortingFlights dsf ON mr.IdTargetFlight=dsf.id
                                            WHERE mr.idOriginalFlight=@iddsfOrig AND mr.Active=1", new { iddsfOrig = idDailySortingFylight }).FirstOrDefault();

                    if(mr!=null)
                    {
                        idDailyFlightsNew = mr.IDFlights;
                        MergeRedirectType = mr.RedirectType;
                        return true;
                    } else
                        return false;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }



        public bool CalculateTransportTimes(int numItem, out string errMessage)
        {
            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    List<TransportTimeView> ttvs = connection.Query<TransportTimeView>(@"select top(" + numItem.ToString() + @") vtp.*,tptc.id,tptc.maximumtime,datediff(ss,dt1,dt2) measuredtime ,tptc.id idTransportTimeConfig
                                                                                    from vwSortedItemsFirstLastTP vtp
                                                                                    join transporttimeconfig tptc on upper(vtp.firsttpname) like upper(replace(tptc.tpstart,'*','%')) and upper(vtp.lasttpname) like upper(replace(tptc.tpend,'*','%'))
                                                                                    where tptc.benabled=1
                                                                                    order by vtp.iditem").ToList();

                    List<TransportTimeMonitoring> ttms = new List<TransportTimeMonitoring>();

                    foreach(TransportTimeView ttv in ttvs)
                    {
                        int subtracted = connection.ExecuteScalar<int>("CalculateTransportTimeSubtracted", new { IDItem = ttv.IDItem, idTPconf = ttv.id }, commandType: CommandType.StoredProcedure);


                        TransportTimeMonitoring ttm = new TransportTimeMonitoring();
                        ttm.IDItem = ttv.IDItem;
                        ttm.TPIDStart = ttv.firstTP;
                        ttm.TPIDEnd = ttv.LastTP;
                        ttm.TimestampStart = ttv.dt1;
                        ttm.TimestampEnd = ttv.dt2;
                        ttm.MeasuredTime = ttv.measuredtime - subtracted;
                        ttm.SubtractedTIme = subtracted;
                        ttm.idTransportTimeConfig = ttv.idTransportTimeConfig;
                        if (ttm.MeasuredTime > ttv.maximumtime * 60)
                            ttm.bStatus = false;
                        else
                            ttm.bStatus = true;

                        ttms.Add(ttm);
                    }

                    if(ttms.Any())
                    {
                        connection.Insert(ttms);

                        //DataTable tvp = new DataTable();
                        //tvp.Columns.Add(new DataColumn("ID", typeof(long)));

                        //// populate DataTable from your List here
                        //foreach (var id in ttms.Select(t=>t.IDItem))
                        //    tvp.Rows.Add(id);

                        connection.Query<TargetSortData>("update item set bTransportTimeCalculated=1 where id in @list",
                            new { list= ttms.Select(t => t.IDItem) }).ToList();
                    }

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool PublishSortplan(DailySorting dailySort, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    var ds = connection.Query<DailySorting>("select * from DailySorting where Published=1 and sortingdate=@sd", new { sd = dailySort.SortingDate }).ToList();

                    if (ds != null && ds.Count() == 0)
                    {
                        connection.Execute("update DailySorting set Published=1 where id=@idsort", new { idsort = dailySort.ID });

                        connection.ExecuteScalar<int?>("Sorting_SendDataAOS", new { idDailySorting = dailySort.ID }, commandType: CommandType.StoredProcedure);
                    }

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<HandlerViewShifts> GetHandlerShifts(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<HandlerViewShifts>(@"select * from HandlerViewShifts").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<HandlerViewShifts>();
            }
        }

        public List<ZRHHandlerViewMailer> GetZRHHandlerViewMailer(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ZRHHandlerViewMailer>(@"select * 
                                                                    from ZRHHandlerViewMailer
                                                                    where bEnabled=1 
                                                                        and (LastSent IS NULL
                                                                        OR (
                                                                            CAST(LastSent AS DATE) != CAST(GETDATE() AS DATE) -- Sent today
                                                                            AND CAST(Getdate() AS TIME) > CAST([Time] AS TIME) -- After scheduled time
                                                                        ))").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ZRHHandlerViewMailer>();
            }
        }

        public ZRHHandlerViewMailer GetZRHHandlerViewMailer(int ID, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ZRHHandlerViewMailer>(@"select * 
                                                                    from ZRHHandlerViewMailer
                                                                    where id=@ID", new {ID=ID}).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }


        public List<ZRHHandlerViewMailerZones> GetZRHHandlerViewMailerZones(int idMailRecord, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ZRHHandlerViewMailerZones>(@"select * 
                                                                    from ZRHHandlerViewMailerZones
                                                                    where idZRHHandlerViewMailer=@id and bEnabled=1", new {id=idMailRecord}).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ZRHHandlerViewMailerZones>();
            }
        }


        public bool SaveZRHHandlerViewMailer(ZRHHandlerViewMailer data, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Update<ZRHHandlerViewMailer>(data);
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }



        public List<DailySorting> GetDailySortingByDate(DateTime SortplanDate, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<DailySorting>(@"select ds.*,isnull(st.name,'') TemplateName
                                                            from DailySorting ds
                                                            left join SortTemplate st on ds.idTemplate=st.id
                                                            where ds.sortingdate=@sd", new { sd = SortplanDate }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<DailySorting>();
            }
        }

        public int GetTargetsStrategy(SortingEdgeData TP, List<int> dests, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                int TPID = TP.MasterTP.HasValue ? TP.MasterTP.Value : TP.EdgeStartID;

                if (useCache)
                {
                    return SortingEdges.Value.Where(se => se.EdgeStartID == TPID && dests.Contains(se.ComponentIndexEnd)).Min(se => se.MultipleTargetsStrategy ?? 1);
                }
                else
                {
                    using (var connection = new SqlConnection(connectionstring))
                    {
                        return connection.ExecuteScalar<int>("SELECT MIN(multipletargetsstrategy) FROM dbo.vwSortingEdges WHERE EdgeStartID=@TPid AND ComponentIndexEnd IN @dest",
                           new { TPid = TP.EdgeStartID, dest = dests });
                    }
                }

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return 2;
            }
        }

        public bool SetFlexOneOPerators(int cntLev2, int cntLev3, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("UPDATE zrhflexone SET NumOperatorsLevel2=@c2,NumOperatorsLevel3=@c3", 
                       new { c2 = cntLev2, c3 = cntLev3 } );
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }
        public List<ClosingTarget> GetClosingTargets(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ClosingTarget>("Sorting_GetClosingTargets", commandType:CommandType.StoredProcedure).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ClosingTarget>();
            }
        }

        public bool UpdateSCADAMessages(string DBName, List<SCADA_Messages> messages, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Open();

                    connection.ChangeDatabase(DBName);
                    return connection.Update(messages);
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<SCADA_Messages> GetSCADAMessages(string DBName, int nMessages, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Open();

                    connection.ChangeDatabase(DBName);

                    string sql = $"select top({nMessages}) * from SCADA_Messages where Processed=0 order by StartTime";

                    return connection.Query<SCADA_Messages>(sql).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage=ex.CompleteMessage();
                return new List<SCADA_Messages>();
            }
        }


        public int? GetSortTemplateByDate(DateTime SortplanDate, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<int?>("GetDefaultSortTemplate", new { refDate = SortplanDate }, commandType: CommandType.StoredProcedure);
                }
            }
            catch(Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public DailySorting CreateSortplanByTemplateDate(int idTemplate, DateTime SortplanDate, string SortplanName, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    var p = new DynamicParameters();
                    p.Add("@idTemplate", idTemplate);
                    p.Add("@dailySortName", SortplanName);
                    p.Add("@refDate", SortplanDate);
                    p.Add("@automatic", 1);
                    p.Add("@automaticEditable", 1);
                    p.Add("@idDailySorting", null, dbType: DbType.Int32, direction: ParameterDirection.Output);

                    connection.Execute("CreateSortplan", p, commandType: CommandType.StoredProcedure);

                    int? idDailySort = p.Get<int?>("@idDailySorting");

                    if (idDailySort == null)
                        return null;
                    else
                    {
                        DailySorting ds = GetDailySortingByDate(SortplanDate, out errMessage).FirstOrDefault();
                        return ds;
                    }
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;

            }
        }

        public DailySorting CreateSortplanByDate(DateTime SortplanDate, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    int res = connection.ExecuteScalar<int>("Sorting_CreateSortplanByDate", new { SortplanRefDate = SortplanDate, EmergencySortplan = false }, commandType: CommandType.StoredProcedure);

                    if (res < 0)
                    {
                        switch (res)
                        {
                            case -1:
                                errMessage = $"No Template for ref date {SortplanDate}";
                                break;
                            case -2:
                                errMessage = $"Sortplan for ref date {SortplanDate} already exists";
                                break;
                            case -3:
                                errMessage = $"Generic error creating sortplan for date {SortplanDate}";
                                break;
                            default:
                                errMessage = $"Unexpected error {res} creating sortplan for date {SortplanDate}";
                                break;
                        }
                        return null;
                    }
                    else if (res == 0)
                    {
                        // try to create an emergency sortplan

                        res = connection.ExecuteScalar<int>("Sorting_CreateSortplanByDate", new { SortplanRefDate = SortplanDate, EmergencySortplan = true }, commandType: CommandType.StoredProcedure);

                        if (res <= 0)
                        {
                            switch (res)
                            {
                                case 0:
                                    errMessage = $"Not possible to create emergency sortplan for ref date {SortplanDate}";
                                    break;
                                case -1:
                                    errMessage = $"No Template for ref date {SortplanDate}";
                                    break;
                                case -2:
                                    errMessage = $"Sortplan for ref date {SortplanDate} already exists";
                                    break;
                                case -3:
                                    errMessage = $"Generic error creating sortplan for date {SortplanDate}";
                                    break;
                                default:
                                    errMessage = $"Unexpected error {res} creating sortplan for date {SortplanDate}";
                                    break;
                            }
                            return null;
                        }
                        else
                        {
                            string errmsg = string.Empty;
                            List<DailySorting> dailySorts = GetDailySortingByDate(SortplanDate, out errmsg);
                            if (dailySorts.Any())
                                return dailySorts[0];
                            else
                                return null;
                        }
                    }
                    else
                    {
                        string errmsg = string.Empty;
                        List<DailySorting> dailySorts=GetDailySortingByDate(SortplanDate, out errmsg);
                        if (dailySorts.Any())
                            return dailySorts[0];
                        else
                            return null;
                    }
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }


        public List<FlowManagerEdges> GetFMEdges(string serverGroup, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<FlowManagerEdges>("select * from FlowManagerEdges where SCADAGroup=isnull(@sg,SCADAGroup)", new {sg=serverGroup}).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<FlowManagerEdges>();
            }
        }
        public List<FlowManagerEdgeData> GetFMEdgeData(string serverGroup, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<FlowManagerEdgeData>(@"select fmed.*,0 bChanged,0 bNew 
                                                                    FROM FlowManagerEdgeData fmed
                                                                    JOIN dbo.FlowManagerEdges fme ON fmed.IDFMEdges = fme.ID
                                                                    WHERE fme.SCADAGroup = ISNULL(@sg, fme.SCADAGroup)", new { sg = serverGroup }).ToList();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<FlowManagerEdgeData>();
            }
        }
        public List<FlowManagerActionPoints> GetFlowManagerActionPoints(string serverGroup, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<FlowManagerActionPoints>("select *,0 bChanged from FlowManagerActionPoints where SCADAGroup = ISNULL(@sg, SCADAGroup)", new { sg = serverGroup }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<FlowManagerActionPoints>();
            }
        }
        public bool SaveFlowManagerEdgeData(List<FlowManagerEdgeData> fmData, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    List<FlowManagerEdgeData> updFM = fmData.Where(f => f.bChanged).ToList();
                    connection.Update(updFM);
                    List<FlowManagerEdgeData> insFM = fmData.Where(f => f.bNew).ToList();
                    connection.Insert(insFM);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }
        public bool SaveFlowManagerActionPoints(List<FlowManagerActionPoints> fmAction, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Update(fmAction);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool RefreshFMAvailability(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute("FM_UpdateAvailability",commandType:CommandType.StoredProcedure);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }


        public List<RoutesDefinition> GetRoutesDefinitions(string serverGroup, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<RoutesDefinition>("select * from RoutesDefinition where SCADAGroup=isnull(@sg,SCADAGroup)", new {sg=serverGroup}).ToList();

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<RoutesDefinition>();
            }
        }
        public bool SaveRouteStatistics(List<RouteStatistics> rstats, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    long n = connection.Insert(rstats);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<vwMESStationStatus> GetMESStationStatus(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<vwMESStationStatus>("select * from vwMESStationStatus ").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<vwMESStationStatus>();
            }
        }

        public List<TargetStatus> GetTargetStatus(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<TargetStatus>(@"SELECT DISTINCT sd.IDTarget,CAST(sd.operationalStatus AS INT) OperationalStatus,csg.SCADAGroup
                                                            FROM dbo.SortDestinations sd
                                                              JOIN component c ON c.ComponentIndex = sd.IDTarget
															  LEFT JOIN ComponentSCadaGroup csg ON csg.idcomponent=c.id
                                                              WHERE c.IDComponentType = 6 AND csg.SCADAGroup IS NOT null").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<TargetStatus>();
            }
        }

        public List<SorterDischarges> GetSorterDischarges(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<SorterDischarges>(@"declare @tab table (AreaName nvarchar(50),SorterName nvarchar(50),SCADAGroup nvarchar(10), Cnt int)

                                                                insert into @tab
                                                                select ppc.name,upper(pc.name),pcs.SCADAGroup,count(distinct ir.id)*4 cnt
                                                                from Component ppc
                                                                join component pc on ppc.id=pc.IDParentComponent
                                                                join ComponentSCADAGroup pcs on pcs.IDComponent=pc.id
                                                                join Component c on pc.id=c.IDParentComponent
                                                                left join ItemResult ir on ir.idtrackingpoint=c.TrackingPointID
                                                                where ir.timestamp>dateadd(minute,-15,getdate())
                                                                group by ppc.name,pc.name ,pcs.SCADAGroup


                                                                insert into @tab
                                                                select ppc.name,upper(pc.name),pcs.SCADAGroup,0 cnt
                                                                from Component ppc
                                                                join component pc on ppc.id=pc.IDParentComponent
                                                                join ComponentSCADAGroup pcs on pcs.IDComponent=pc.id
                                                                join Component c on pc.id=c.IDParentComponent
                                                                left join @tab t on t.AreaName=ppc.name and t.SorterName=pc.name 
                                                                where t.AreaName is null
                                                                group by ppc.name,pc.name ,pcs.SCADAGroup

                                                                select * from @tab").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<SorterDischarges>();
            }
        }



        public List<vwAreaEDSCount> GetAreaEDSCount(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<vwAreaEDSCount>(@"SELECT * from vwAreaEDSCount").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<vwAreaEDSCount>();
            }
        }
        public int GetFlexOneStats(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<int>(@"	select count(DISTINCT cs.idcomponent)
                                                            from ComponentStatus cs
                                                            join Component c on cs.IDComponent=c.ComponentIndex and cs.IDPLC=c.IDPLCNode
                                                            join ComponentType ct on c.IDComponentType=ct.ID
                                                            where ct.name='XRay' and ((cs.cnt1+cs.cnt2)=0 or cs.cnt3=1)");
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return 0;
            }
        }

        public bool SetGlobalConfig(string parName, string parValue, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"update configGlobal set [value]=@v where [name]=@n", new { v = parValue, n = parName });
                    return true;
                }

            }
            catch(Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public List<SorterStatus> GetSorterStatus(string serverGroup, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<SorterStatus>(@"select * from SorterStatus where SCADAGroup=isnull(@sg,SCADAGroup)", new {sg=serverGroup}).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<SorterStatus>();
            }

        }

        public SorterStatus GetSorterStatus(int IDComponent, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<SorterStatus>(@"select * from SorterStatus where idcomponent=@id", new { id = IDComponent }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }


        public bool UpdateSorterStatus(List<SorterStatus> sorterStatus, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Update(sorterStatus);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<SortEventsTypes> LoadEventTypes(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    try
                    {
                        return connection.Query<SortEventsTypes>("SELECT DISTINCT componentindex,PreliminarySortEventType,FinalSortEventType,TrackingPointID FROM vwalltargets").ToList();
                    }
                    catch(Exception ex)
                    {
                        return connection.Query<SortEventsTypes>("SELECT DISTINCT componentindex,PreliminarySortEventType,FinalSortEventType FROM vwalltargets").ToList();
                    }
                } 

            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<SortEventsTypes>();
            }
        }


        public List<ControlRoomLogConfig> GetEnabledControlRoomConfigs(out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ControlRoomLogConfig>(@"select * from controlroomlogconfig where benabled = 1").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ControlRoomLogConfig>();
            }
        }

        public List<vwlogbook> GetLogBookEntries(string eventType, string likeCondition, DateTime? lastCheck, out DateTime? newLastCheck, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    newLastCheck = DateTime.Now;
                    return connection.Query<vwlogbook>(@"select * from vwlogbook 
                                                         where eventtype=@evtype
                                                         and MessageParams like @likeCond 
                                                         and timestamp>isnull(@dt,'2000-01-01')", new {evtype=eventType,likeCond=likeCondition,dt=lastCheck}).ToList();
                }
            }
            catch (Exception ex)
            {
                newLastCheck = null;
                errMessage = ex.CompleteMessage();
                return new List<vwlogbook>();
            }
        }

        public List<ChronologicalMessages> GetChronologicalMessages(List<string> alarmKeys, DateTime minDT, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<ChronologicalMessages>(@"SELECT * FROM dbo.ChronologicalMessages 
                                                                    WHERE TimestampFrom>=@mindate AND alarmkey IN @keys", new { mindate=minDT, keys = alarmKeys }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<ChronologicalMessages>();
            }
        }



        public bool SaveChronologicalMessages(List<ChronologicalMessages> insmsgs, List<ChronologicalMessages> updmsgs, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Update(updmsgs);

                    connection.Insert(insmsgs);
                    return true;
                }
            }
            catch(Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public List<ChronologicalMessages> GetChronologicalMessages(string eventType, string likeCondition, int? duration, DateTime? lastCheck, out DateTime? newLastCheck, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    newLastCheck = DateTime.Now;
                    return connection.Query<ChronologicalMessages>(@"UPDATE cs SET bControlRoomLog=1
                                                                        OUTPUT Inserted.*
                                                                        FROM ChronologicalMessages cs
                                                                        where cs.bControlRoomLog=0
                                                                        AND cs.eventtype=@evtype
                                                                        and cs.EventText like @likeCond 
                                                                        and isnull(duration,0)>=@dur
                                                                        and timestampFrom>dateadd(day,-3,CAST(GETDATE() AS DATE))", 
                                                         new { evtype = eventType, likeCond = likeCondition, dur=duration??0 }).ToList();
                }
            }
            catch (Exception ex)
            {
                newLastCheck = null;
                errMessage = ex.CompleteMessage();
                return new List<ChronologicalMessages>();
            }
        }

        public bool UpdateControlRoomLogRulesLastCheck(int ID, DateTime lastCheck, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"Update controlroomlogconfig set LastCheckTS=@ts where ID=@id", new { ts = lastCheck, id= ID});
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public bool InsertControlRoomLogs(List<GW.ControlRoomLog> logs, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Insert(logs);
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }


        }

        public Dictionary<string, string> GetLogBookEventsLoc(string culture, out string errMessage)
        {
            errMessage = string.Empty;
            Dictionary<string, string> retDict = new Dictionary<string, string>();
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    var messages = 
                        connection.Query(@"select l.eventType,ll.descr
                                                                from [dbo].[LogBookEventTypes] l
                                                                join [dbo].[LogBookEventTypesLocalization] ll on l.id=ll.[IDLogBookEventTypes]
                                                                where ll.culture=@cult", new { cult = culture}).ToList();

                    foreach(var t in messages)
                    {
                        retDict[t.eventType] = t.descr;
                    }
                    return retDict;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retDict;
            }
        }

        public Dictionary<string, string> GetChronoEventsLoc(string culture, out string errMessage)
        {
            errMessage = string.Empty;
            Dictionary<string, string> retDict = new Dictionary<string, string>();
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    var messages =
                        connection.Query(@"select c.eventtype,cl.descr
                                                                from [dbo].[ChronologicalMessagesEnum] c
                                                                join [dbo].[ChronologicalMessagesEnumLocalization] cl on c.eventtype=cl.eventtype
                                                                where cl.culture=@cult", new { cult = culture }).ToList();

                    foreach (var t in messages)
                    {
                        retDict[t.eventtype] = t.descr;
                    }
                    return retDict;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return retDict;
            }
        }

        public vwManualStations GetManualStation(int componentIndex, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    return connection.Query<vwManualStations>(@"select * from vwManualStations where componentIndex=@index", new { index = componentIndex }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }

        }


        public List<vwInactiveEtdStations> GetInactiveEtdStations(out string errMessage)
        {
            errMessage = string.Empty;
            List<vwInactiveEtdStations> retDict = new List<vwInactiveEtdStations>();
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {

                    return connection.Query<vwInactiveEtdStations>(@"select * from vwInactiveEtdStations").ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<vwInactiveEtdStations>();
            }

        }

        public List<vwChangedMesStations> GetChangedMesStations(out string errMessage)
        {
            errMessage = string.Empty;
            List<vwChangedMesStations> retDict = new List<vwChangedMesStations>();
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    // also reset the notifystatuschange on mesconfiguration so that we send only updated status values
                    var val = connection.Query<vwChangedMesStations>(@"select * from vwChangedMesStations; update MESConfiguration set NotifyStatusChange=0 where NotifyStatusChange=1;").ToList();
                    return val;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<vwChangedMesStations>();
            }

        }


        public bool UpdateInactiveEtdStations(List<string> stationNameList, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"Update etdstation set ActiveStatus=0 where name in @name", new { name = stationNameList});
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public List<TargetSortData> GetSpecialMES(List<SortingEdgeData> edges)
        {
            return edges.Where(e => e.Policy == "Mes").Select(e => new TargetSortData() { Target = e.ComponentIndexEnd, TargetTPid = e.EdgeEndID }).ToList();
        }


        public LAP.LegacySorterParamResponseMsg GetSorterParams(LAP.LegacySorterParamRequestMsg req, out string errMessage)
        {
            errMessage = string.Empty;
            int idSorterComponent;
            int idSortingAreaComponent;
            string areaName;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    idSortingAreaComponent = connection.ExecuteScalar<int>(@"SELECT pc.ID
                                                                FROM dbo.Component c
                                                                JOIN dbo.ComponentType ct ON c.IDComponentType=ct.ID
                                                                JOIN dbo.Component pc ON c.IDParentComponent=pc.ID
                                                                WHERE ct.Name='Sorter' AND c.IDPLCNode=@idsorter", new { idsorter = req.IdSorter });

                    areaName= connection.ExecuteScalar<string>(@"SELECT pc.name
                                                                FROM dbo.Component c
                                                                JOIN dbo.ComponentType ct ON c.IDComponentType=ct.ID
                                                                JOIN dbo.Component pc ON c.IDParentComponent=pc.ID
                                                                WHERE ct.Name='Sorter' AND c.IDPLCNode=@idsorter", new { idsorter = req.IdSorter });


                    idSorterComponent = connection.ExecuteScalar<int>(@"SELECT c.id 
                                                                FROM dbo.Component c
                                                                JOIN dbo.ComponentType ct ON c.IDComponentType=ct.ID
                                                                WHERE ct.Name='Sorter' AND c.IDPLCNode=@idsorter", new { idsorter = req.IdSorter });


                    errMessage = string.Format($"idSortingArea {idSortingAreaComponent} AreaName {areaName} idSorterComponent {idSorterComponent}");


                    short Risiko = connection.ExecuteScalar<short>(@"SELECT idSortingDestinations FROM dbo.ConfigSpecialSorts WHERE SortArea = @sortarea AND SpecialSortType = 'NOTOK'", new { sortarea = idSortingAreaComponent });
                    short RecCount = connection.ExecuteScalar<short>(@"SELECT RecirculationThreshold FROM dbo.SorterRecirculations WHERE idsorter=@idsorter", new { idsorter= idSorterComponent });
                    GlobalConf.Refresh();
                    int altNoRead = (int)(GlobalConf[areaName+"_ALT_NOREAD"]??414);

                    return new LAP.LegacySorterParamResponseMsg(req.Envelope.DestinationNode, req.Envelope.SourceNode, 0, req.IdSorter, RecCount, (short) altNoRead, Risiko);

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public bool ResetSorterEdgeStandbyTimer(int SorterComponent, out string errMessage)
        {
            int FMEdgeID;
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    FMEdgeID = connection.ExecuteScalar<int>(@"SELECT ID from FlowManagerEdges where idSorterComponent=@c", new { c= SorterComponent});

                    connection.Execute(@"update dbo.FlowManagerEdgeData SET BaggageCountTS=GETDATE() WHERE IDFMEdges=@id", new { id = FMEdgeID });

                    return true;

                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }


        }


        public List<SCADATagsLog> GetSCADATagsLogs(string SCADAServer, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<SCADATagsLog>(@"select * from SCADATagsLog WHERE SCADAServer=@srv", new { srv = SCADAServer}).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<SCADATagsLog>();
            }
        }
        public bool SaveSCADATagsLogs(List<SCADATagsLog> logs, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Update(logs.Where(l => l.ID != 0));

                    connection.Insert(logs.Where(l => l.ID == 0));
                    
                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }

        public int GetBulkyTarget(int Target, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return (int) connection.ExecuteScalar("select dbo.CalculateBulkyTarget(@target)", new {target=Target});
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return Target;
            }
        }

        public MESStationConfiguration GetMESStationConfiguration(string stationName,out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<MESStationConfiguration>(@"select * 
                                                                       from MESStationConfiguration
                                                                       where FunctionName=@name", new { name = stationName }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public MESConfiguration GetMESConfiguration(string stationName, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<MESConfiguration>(@"select * 
                                                                       from MESConfiguration
                                                                       where FunctionName=@name", new { name = stationName }).FirstOrDefault();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return null;
            }
        }

        public int GetValidSortersForTargets(List<int> targets, out string errMessage)
        {
            errMessage = String.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return  connection.ExecuteScalar<int>(@"select count(*) cnt 
                                                            from (select distinct isnull(ss.id,-1) id, 
                                                            case when (ss.id is null or ss.Stopped=0 OR (ss.stopped=1 and isnull(ss.lastCommandSentTS,getdate())>dateadd(minute,-10,getdate()))) then 1 else 0 end [Stopped]
                                                            from sortingedgestart ses 
                                                            join sortingedgeend see on ses.TPIDStart =see.TPIDStart
                                                            join component c on ses.TPIDStart=c.TrackingPointID
                                                            join component ctgt on see.TPIDEnd=ctgt.TrackingPointID
                                                            left join sorterstatus ss on ss.IDComponent =c.IDParentComponent 
                                                            where ses.Context='F' and ctgt.ComponentIndex =@tgt) a
                                                            where a.[Stopped]=1", new { tgt = targets});
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return 0;
            }
        }

        public bool CheckHBSTrackingLostReset(long VID, int TPid, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                long plc = (VID % 1000000000L) / 1000000L;
                if (HBSLostPLC.Value.Any(p => p.PLC == plc) && HBSLostTP.Value.Any(p => p.TPCheck == TPid))
                    return true;
                else
                    return false;
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }


        public bool CheckBagBlacklist(string bagid, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    int cnt= connection.ExecuteScalar<int>(@"select count(*) 
                                                            from BagBlacklist  
                                                            where bagid=@bid and timestamp>dateadd(day,-1,getdate())", new { big = bagid });

                    return (cnt > 0) ? true:false ;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public bool RemoveBagBlacklist(string bagid, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Execute(@"delete BagBlacklist  
                                                            where bagid=@bid", new { big = bagid });

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }

        }

        public int? GetSorterRecirculations(int TPid, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<int?>(@"select sr.RecirculationThreshold 
                                                            from SorterRecirculations sr
                                                            join component c on c.IDParentComponent=sr.IDSorter
                                                            where c.TrackingPointID =@tp", new { tp = TPid});
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return 0;
            }
        }

        public List<int> FilterNotReachableTargets(SortingEdgeData TP, List<TargetSortData> FinalTargets, out string errMessage)
        {
            errMessage= string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.Query<int>(@"select cs.idcomponent
                                                    from FilterNotReachableTargets ft
                                                    join componentstatus cs on cs.idcomponent=ft.Target
                                                    join componentstatusenum cse on cs.IDStatus =cse.ID
                                                    where ft.TPID =@tp and ft.Target in @tgts 
                                                    and cse.bCanDischarge=0", new { tp = TP.MasterTP??TP.EdgeStartID,tgts=FinalTargets.Select(ft=>ft.Target).ToList() }).ToList();
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return new List<int>();
            }
        }

        public string GetSCADATag(string tagName, out string errMessage)
        {
            errMessage = string.Empty;

            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return (connection.ExecuteScalar<string>(@"select TagValue from SCADATags where tagname=@n", new { n = tagName }) ?? String.Empty);
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return string.Empty;
            }
        }

        public virtual BSMData GetBSMFromRElement(string bagid, string flight, out string errMessage)
        {
            errMessage = string.Empty;
            return null;
        }

        public int? GetCDGPreTri(string fLCinb, int fLNinb, string fLXinb, int dayOfWeekMondayBased)
        {
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    return connection.ExecuteScalar<int?>(@"select top(1) Trg from CDGPreTri where TRIM(FLC)=TRIM(@FLC) and FLN=@FLN and isnull(TRIM(FLX), '')=isnull(TRIM(@FLX), '') and Weekday=@Weekday", new { FLC=fLCinb, FLN=fLNinb, FLX=fLXinb, Weekday=dayOfWeekMondayBased });
                }
            }
            catch (Exception ex)
            {
                return null;
            }
        }
        public virtual List<TargetSortData> SortByDepose(long itemId, out string errMessage)
        {
            errMessage = string.Empty;
            return new List<TargetSortData>();
        }

        public void RemoveItemBarcodesByComponent(Item item, int componentIndex)
        {
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    var res = connection.Execute(@"delete from dbo.ItemBarcode where IDItem=@IDItem and IDComponent=@componentIndex", new { IDItem = item.ID, componentIndex });
                    if (res > 0)
                        item.itemBarcodes.RemoveAll(b => b.IDComponent == componentIndex);
                }
            }
            catch (Exception ex)
            {
            }
        }

        public bool InsertBagInBlackList(string bagId, out string errMessage)
        {
            BagBlacklist bbl = new BagBlacklist();
            bbl.BagId = bagId;

            errMessage = string.Empty;
            try
            {
                using (var connection = new SqlConnection(connectionstring))
                {
                    connection.Insert<GW.BagBlacklist>(bbl);

                    return true;
                }
            }
            catch (Exception ex)
            {
                errMessage = ex.CompleteMessage();
                return false;
            }
        }
    }
}
