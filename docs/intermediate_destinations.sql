USE [CDG_SACDB]
GO
/****** Object:  StoredProcedure [dbo].[Sorting_GetIntermediateTargets]    Script Date: 29/09/2026 17:38:11 ******/
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
-- =============================================
-- Author:    <Author,,Name>
-- Create date: <Create Date,,>
-- Description:  <Description,,>
-- =============================================


ALTER PROCEDURE [dbo].[Sorting_GetIntermediateTargets]
  @IDSnapshot INT,
  @TPid int,
  @FinalTargets dbo.IntList READONLY,
  @SorterPrio NVARCHAR(20) null
AS
BEGIN
DECLARE @Snapshots AS TABLE (ID INT)

  SET NOCOUNT ON;

  INSERT INTO @Snapshots
SELECT 0 ID

  INSERT INTO @Snapshots
SELECT TOP(1) fmsnap.ID 
FROM dbo.FlowManagerPathSnapshots fmsnap
LEFT JOIN flowmanageractionpoints fma ON fma.tpid=@TPid
CROSS JOIN dbo.ConfigGlobal cg
WHERE DATEDIFF(MINUTE,updatets,GETDATE())<5
AND ISNULL(fma.CommandMode,3)=3 
AND cg.name='FM_ENABLED' AND LOWER(ISNULL(value,'true'))='true'
ORDER BY fmsnap.UpdateTS DESC


SELECT * from
(SELECT 
TOP 1 WITH TIES 
idsnapshot,[Target],TargetTPid,a.OutputRule,MIN(weight) Weight, SorterPrioOrder,SorterPrioOrder*1000+(floor(MIN(weight)/10)) orderfield from
(SELECT fmp.idsnapshot,e2.ComponentIndexEnd [Target],fmp.TPSortingDestination TargetTPid,e2.OutputRule,
[weight]+e2.EdgeWeight+e.EdgeWeight+(CASE WHEN ISNULL(c2ch.bCanDischarge,1)=1 THEN 0 ELSE 5 END) [Weight],CASE WHEN e2.tpsorterend=@sorterPrio THEN 0 ELSE 1 END SorterPrioOrder
FROM dbo.FlowManagerPaths fmp
JOIN vwsortingedgesLite e ON e.EdgeStartID=fmp.TPDestination
JOIN vwSortingEdgesLite e2 ON e2.EdgeStartID=fmp.TPStart AND e2.EdgeEndID=fmp.TPSortingDestination
JOIN @Snapshots sn ON sn.id=fmp.IDSnapshot
LEFT JOIN dbo.vwComponentStatusLite c1ch ON c1ch.TrackingPointId=e2.EdgeEndID
LEFT JOIN dbo.vwComponentStatusLite c2ch ON c2ch.TrackingPointId=e.EdgeEndID
WHERE e.context IN ('E','F') AND e.ComponentIndexEnd IN (SELECT id FROM @FinalTargets) AND tpstart=@tpid 
AND ISNULL(e2.dischargeStatus,ISNULL(c1ch.bCanDischarge,1))=1
) a
GROUP BY a.IDSnapshot,[Target],TargetTPid,a.OutputRule, SorterPrioOrder
ORDER BY a.IDSnapshot DESC, SorterPrioOrder*1000+(floor(MIN(weight)/10))
) b ORDER BY  b.Weight

END

 